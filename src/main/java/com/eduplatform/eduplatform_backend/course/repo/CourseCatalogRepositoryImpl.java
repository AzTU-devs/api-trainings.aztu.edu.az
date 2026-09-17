package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The public catalogue query, hand-assembled.
 *
 * <p>Three things rule out a {@code @Query} method here:
 * <ul>
 *   <li>relevance needs Postgres' {@code @@} operator over {@code to_tsvector} /
 *       {@code plainto_tsquery}, which JPQL cannot express;</li>
 *   <li>a static query would have to guard every filter with
 *       {@code (:param is null or col = :param)}, which stops Postgres using the
 *       catalogue indexes even for the common bare browse — here a predicate is
 *       emitted only when its filter is actually supplied;</li>
 *   <li>Spring Data cannot apply a {@code Sort} to a native query (it appends the
 *       raw property name, which is not a column), so ORDER BY is built from a
 *       whitelist below.</li>
 * </ul>
 *
 * <p>Only compile-time SQL fragments are ever concatenated; every value that came
 * from a caller is a bound parameter.
 */
public class CourseCatalogRepositoryImpl implements CourseCatalogRepository {

    /** Mirrors the expression behind {@code idx_courses_search} so the GIN index applies. */
    private static final String SEARCH_DOCUMENT =
            "to_tsvector('simple', c.title || ' ' || coalesce(c.subtitle,'') || ' ' || coalesce(c.description,''))";

    private static final String SEARCH_QUERY = "plainto_tsquery('simple', :q)";

    /**
     * Course length in seconds, defined once for both course types so a single bucket
     * filter covers the whole catalogue. NULL when the type-specific row is missing,
     * which excludes the course from every bucket — exactly the courses whose
     * {@code totalDurationSec} comes back null on the card.
     */
    private static final String DURATION_SECONDS = """
            case c.course_type
                when 'ONLINE' then ocd.total_video_seconds
                when 'OFFLINE' then round(fcd.total_hours * 3600)
            end""";

    /**
     * Sortable columns, keyed by the entity property clients pass in {@code sort}.
     * Anything outside this map is rejected rather than interpolated — this is the one
     * place a request could otherwise reach the SQL text.
     */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "publishedat", "c.published_at",
            "createdat", "c.created_at",
            "title", "c.title",
            "price", "c.price",
            "ratingavg", "c.rating_avg",
            "ratingcount", "c.rating_count",
            "enrolledcount", "c.enrolled_count");

    private final EntityManager em;

    public CourseCatalogRepositoryImpl(EntityManager em) {
        this.em = em;
    }

    @Override
    public Page<Course> browseCatalog(CourseStatus status, CourseCatalogFilter filter, Pageable pageable) {
        Where where = whereClause(status, filter);
        String fromClause = fromClause(where.joinsDetails());

        Query idQuery = em.createNativeQuery(
                "select c.id" + fromClause + " where " + where.sql() + orderByClause(pageable.getSort(), filter),
                UUID.class);
        where.params().forEach(idQuery::setParameter);
        if (pageable.isPaged()) {
            idQuery.setFirstResult((int) pageable.getOffset());
            idQuery.setMaxResults(pageable.getPageSize());
        }
        List<?> rows = idQuery.getResultList();
        List<UUID> ids = rows.stream().map(UUID.class::cast).toList();

        Query countQuery = em.createNativeQuery("select count(*)" + fromClause + " where " + where.sql());
        where.params().forEach(countQuery::setParameter);
        long total = ((Number) countQuery.getSingleResult()).longValue();

        return new PageImpl<>(loadSummaries(ids), pageable, total);
    }

    /** The assembled WHERE clause and the values to bind into it. */
    private record Where(String sql, Map<String, Object> params, boolean joinsDetails) {}

    private static Where whereClause(CourseStatus status, CourseCatalogFilter filter) {
        // Native SQL bypasses the @SQLRestriction on Course, so soft deletes are excluded here.
        StringBuilder sql = new StringBuilder("c.deleted_at is null and c.status = :status");
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("status", status.name());

        if (filter.type() != null) {
            sql.append(" and c.course_type = :type");
            params.put("type", filter.type().name());
        }
        if (filter.categoryId() != null) {
            // exists() rather than a join: a course sitting in two categories must not
            // appear twice, and the count has to stay exact.
            sql.append(" and exists (select 1 from course_categories cc"
                    + " where cc.course_id = c.id and cc.category_id = :categoryId)");
            params.put("categoryId", filter.categoryId());
        }
        if (filter.level() != null) {
            sql.append(" and c.level = :level");
            params.put("level", filter.level().name());
        }
        if (filter.language() != null) {
            // Language codes are stored lower-case but arrive from links and bookmarks
            // in any case, so compare case-insensitively.
            sql.append(" and lower(c.language) = lower(:language)");
            params.put("language", filter.language());
        }
        if (filter.free() != null) {
            sql.append(" and c.is_free = :free");
            params.put("free", filter.free());
        }
        if (filter.priceMin() != null) {
            sql.append(" and c.price >= :priceMin");
            params.put("priceMin", filter.priceMin());
        }
        if (filter.priceMax() != null) {
            sql.append(" and c.price <= :priceMax");
            params.put("priceMax", filter.priceMax());
        }
        if (filter.ratingMin() != null) {
            sql.append(" and c.rating_avg >= :ratingMin");
            params.put("ratingMin", filter.ratingMin());
        }
        CourseCatalogFilter.DurationBucket bucket = filter.durationBucket();
        if (bucket != null) {
            if (bucket.minSecondsInclusive() != null) {
                sql.append(" and ").append(DURATION_SECONDS).append(" >= :durationMin");
                params.put("durationMin", bucket.minSecondsInclusive());
            }
            if (bucket.maxSecondsExclusive() != null) {
                sql.append(" and ").append(DURATION_SECONDS).append(" < :durationMax");
                params.put("durationMax", bucket.maxSecondsExclusive());
            }
        }
        if (filter.q() != null) {
            // Same matching as the search endpoint has always used: the full-text index
            // first, with an ILIKE fallback for the partial words plainto_tsquery drops.
            sql.append(" and (").append(SEARCH_DOCUMENT).append(" @@ ").append(SEARCH_QUERY)
                    .append(" or c.title ilike concat('%', :q, '%'))");
            params.put("q", filter.q());
        }
        return new Where(sql.toString(), params, bucket != null);
    }

    /** The 1:1 detail tables are joined only when the duration filter needs to read them. */
    private static String fromClause(boolean joinsDetails) {
        return joinsDetails
                ? " from courses c"
                        + " left join online_course_details ocd on ocd.course_id = c.id"
                        + " left join offline_course_details fcd on fcd.course_id = c.id"
                : " from courses c";
    }

    private static String orderByClause(Sort sort, CourseCatalogFilter filter) {
        StringBuilder sql = new StringBuilder(" order by ");
        if (sort.isSorted()) {
            sql.append(sort.stream()
                    .map(order -> columnFor(order.getProperty()) + (order.isAscending() ? " asc" : " desc") + " nulls last")
                    .collect(Collectors.joining(", ")));
        } else if (filter.q() != null) {
            // An unsorted search is ranked: full-text hits first, the ILIKE-only
            // fallback matches (rank 0) after them.
            sql.append("ts_rank(").append(SEARCH_DOCUMENT).append(", ").append(SEARCH_QUERY)
                    .append(") desc, c.published_at desc nulls last");
        } else {
            sql.append("c.published_at desc nulls last");
        }
        // Unique tiebreaker: courses published in the same instant would otherwise be
        // ordered arbitrarily per query, so a row could repeat on page 2 and be missed
        // on page 1.
        return sql.append(", c.id desc").toString();
    }

    private static String columnFor(String property) {
        String column = SORTABLE_COLUMNS.get(property.replace("_", "").toLowerCase(Locale.ROOT));
        if (column == null) {
            throw Errors.badRequest("INVALID_SORT_PROPERTY", "Courses cannot be sorted by '" + property
                    + "'; use one of publishedAt, createdAt, title, price, ratingAvg, ratingCount, enrolledCount");
        }
        return column;
    }

    /**
     * Second half of the id-then-fetch pattern: one select that brings back every
     * association the summary mapper reads. Fetch joins and SQL pagination cannot be
     * combined, so the page is narrowed to ids above and hydrated here — two queries
     * per catalogue page instead of two per row.
     */
    private List<Course> loadSummaries(List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        Map<UUID, Course> byId = em.createQuery("""
                        select c from Course c
                          left join fetch c.tutor authorized
                          left join fetch authorized.user
                          left join fetch c.tutors roster
                          left join fetch roster.user
                          left join fetch c.onlineDetails
                          left join fetch c.offlineDetails
                        where c.id in :ids
                        """, Course.class)
                .setParameter("ids", ids)
                .getResultList().stream()
                .collect(Collectors.toMap(Course::getId, Function.identity(), (first, duplicate) -> first,
                        LinkedHashMap::new));
        // Restore the order the filtered query decided on; "in :ids" has none of its own.
        return ids.stream().map(byId::get).filter(Objects::nonNull).toList();
    }
}
