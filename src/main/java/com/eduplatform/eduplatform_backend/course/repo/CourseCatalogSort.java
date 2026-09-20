package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.data.domain.Sort;

import java.util.Locale;
import java.util.Map;

/**
 * The sort properties the public catalogue accepts, and the columns they order by.
 *
 * <p>This is the whole of what an anonymous caller may sort courses by. It is kept apart from
 * {@link CourseCatalogRepositoryImpl} because not every catalogue request reaches the query:
 * free-only mode answers {@code free=false} with an empty page straight from the service, and
 * that answer has to reject a bad {@code sort} exactly as a real query would, so the service
 * checks it here first.
 *
 * <p>Every entry is a column of {@code courses} itself. A nested path into another entity
 * (the tutor, their user account, categories) is never listed: ordering public results by a
 * field the response does not show turns the ordering into an oracle for that field.
 */
public final class CourseCatalogSort {

    /**
     * Sortable columns, keyed by the entity property clients pass in {@code sort}, normalised
     * by {@link #normalise}. Anything outside this map is rejected rather than interpolated —
     * the catalogue's ORDER BY is native SQL, so this is the one place a request could
     * otherwise reach the SQL text.
     */
    private static final Map<String, String> SORTABLE_COLUMNS = Map.of(
            "publishedat", "c.published_at",
            "createdat", "c.created_at",
            "title", "c.title",
            "price", "c.price",
            "ratingavg", "c.rating_avg",
            "ratingcount", "c.rating_count",
            "enrolledcount", "c.enrolled_count");

    private CourseCatalogSort() {
    }

    /** Rejects the request with 400 INVALID_SORT_PROPERTY unless every order in it is whitelisted. */
    public static void requireSortable(Sort sort) {
        sort.forEach(order -> columnFor(order.getProperty()));
    }

    /** The SQL column behind a whitelisted sort property; 400 INVALID_SORT_PROPERTY for any other. */
    static String columnFor(String property) {
        String column = SORTABLE_COLUMNS.get(normalise(property));
        if (column == null) {
            throw Errors.badRequest("INVALID_SORT_PROPERTY", "Courses cannot be sorted by '" + property
                    + "'; use one of publishedAt, createdAt, title, price, ratingAvg, ratingCount, enrolledCount");
        }
        return column;
    }

    /** Accepts the camelCase property, its snake_case column spelling, and any letter case. */
    private static String normalise(String property) {
        return property.replace("_", "").toLowerCase(Locale.ROOT);
    }
}
