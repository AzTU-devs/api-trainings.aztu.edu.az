package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import jakarta.persistence.criteria.Predicate;
import org.hibernate.query.criteria.HibernateCriteriaBuilder;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The query behind {@link CourseRepository#searchTutorUserCourses}: a tutor's own courses,
 * drafts included, narrowed by an optional status and an optional substring.
 *
 * <p>Substring matching rather than the catalog's full-text index: this searches one tutor's
 * courses, where the row count is small and "mach" finding "Machine Learning" is more useful
 * than stemmed whole-word matching. The public catalog keeps its GIN/tsvector path.
 */
public final class TutorCourseSearch {

    /** Newest first, with the id as the unique tiebreaker that makes paging deterministic. */
    public static final Sort NEWEST_FIRST = Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id"));

    /**
     * Stated explicitly on every match: Hibernate's PostgreSQL dialect otherwise emits
     * {@code escape ''}, which switches escaping off, so an escaped wildcard would be matched
     * as a literal backslash followed by a wildcard.
     */
    static final char LIKE_ESCAPE = '\\';

    private TutorCourseSearch() {}

    /**
     * @param status null for every status
     * @param q      null or blank for no text filter; otherwise matched case-insensitively anywhere in
     *               the title, subtitle or slug, with its own {@code %} and {@code _} taken
     *               literally
     */
    public static Specification<Course> of(UUID userId, CourseStatus status, String q) {
        return (root, query, cb) -> {
            List<Predicate> where = new ArrayList<>(3);
            where.add(cb.equal(root.get("tutor").get("user").get("id"), userId));
            if (status != null) {
                where.add(cb.equal(root.get("status"), status));
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + escapeLike(q) + "%";
                // ILIKE, as the public catalog uses, so Postgres folds case on both sides by the
                // same rules; lowering the pattern in Java would apply the JVM's rules instead,
                // which differ for letters such as the Azerbaijani İ. The String-pattern
                // overload is used on purpose: Hibernate binds that as a parameter, whereas
                // cb.literal() would write the caller's text into the SQL itself.
                HibernateCriteriaBuilder hcb = (HibernateCriteriaBuilder) cb;
                where.add(cb.or(
                        hcb.ilike(root.<String>get("title"), pattern, LIKE_ESCAPE),
                        hcb.ilike(root.<String>get("subtitle"), pattern, LIKE_ESCAPE),
                        hcb.ilike(root.<String>get("slug"), pattern, LIKE_ESCAPE)));
            }
            return cb.and(where.toArray(Predicate[]::new));
        };
    }

    /**
     * Makes every character of {@code raw} match itself. The escape character goes first, or
     * the backslashes added for {@code %} and {@code _} would be doubled again.
     */
    static String escapeLike(String raw) {
        return raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
