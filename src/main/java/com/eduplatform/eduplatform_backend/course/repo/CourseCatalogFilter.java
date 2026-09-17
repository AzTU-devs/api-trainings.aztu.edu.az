package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.common.enums.CourseLevel;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.error.Errors;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Every constraint the public catalogue accepts, in one value.
 *
 * <p>A {@code null} field means "no constraint". {@code CourseCatalogRepositoryImpl}
 * emits SQL only for the fields that are set, so a bare browse stays a plain index
 * scan instead of the chain of {@code (:param is null or col = :param)} predicates
 * Postgres cannot plan an index for.
 */
public record CourseCatalogFilter(
        String q,
        CourseType type,
        UUID categoryId,
        CourseLevel level,
        String language,
        Boolean free,
        BigDecimal priceMin,
        BigDecimal priceMax,
        BigDecimal ratingMin,
        DurationBucket durationBucket
) {

    public CourseCatalogFilter {
        // An untouched search box and an unselected language both arrive as "";
        // treating them as absent keeps the query from matching on an empty string.
        q = blankToNull(q);
        language = blankToNull(language);
    }

    /** The search endpoint: free text, no other constraint. */
    public static CourseCatalogFilter byQuery(String q) {
        return new CourseCatalogFilter(q, null, null, null, null, null, null, null, null, null);
    }

    /** This filter with {@code free} forced on — see free-only mode in {@code CourseService}. */
    public CourseCatalogFilter freeOnly() {
        return new CourseCatalogFilter(q, type, categoryId, level, language, Boolean.TRUE,
                priceMin, priceMax, ratingMin, durationBucket);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * The duration ranges the catalogue sidebar offers, measured on the derived course
     * length (online video seconds, or offline contact hours converted to seconds).
     * Bounds are min-inclusive / max-exclusive so the four buckets tile the range
     * without overlapping.
     */
    public enum DurationBucket {

        LT2("lt2", null, 7_200),
        TWO_TO_SIX("2to6", 7_200, 21_600),
        SIX_TO_SEVENTEEN("6to17", 21_600, 61_200),
        GT17("gt17", 61_200, null);

        private final String wireValue;
        private final Integer minSecondsInclusive;
        private final Integer maxSecondsExclusive;

        DurationBucket(String wireValue, Integer minSecondsInclusive, Integer maxSecondsExclusive) {
            this.wireValue = wireValue;
            this.minSecondsInclusive = minSecondsInclusive;
            this.maxSecondsExclusive = maxSecondsExclusive;
        }

        public Integer minSecondsInclusive() {
            return minSecondsInclusive;
        }

        public Integer maxSecondsExclusive() {
            return maxSecondsExclusive;
        }

        /**
         * Resolves the wire value the catalogue uses ({@code 2to6}). These cannot be enum
         * constant names — they start with a digit — so Spring's enum converter cannot bind
         * them and the request parameter is taken as text and parsed here instead.
         */
        public static DurationBucket fromWireValue(String value) {
            if (value == null || value.isBlank()) return null;
            String trimmed = value.trim();
            for (DurationBucket bucket : values()) {
                if (bucket.wireValue.equalsIgnoreCase(trimmed)) return bucket;
            }
            throw Errors.badRequest("INVALID_DURATION_BUCKET",
                    "durationBucket must be one of lt2, 2to6, 6to17, gt17");
        }
    }
}
