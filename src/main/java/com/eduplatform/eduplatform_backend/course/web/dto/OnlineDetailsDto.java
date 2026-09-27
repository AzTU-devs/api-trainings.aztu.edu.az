package com.eduplatform.eduplatform_backend.course.web.dto;

/**
 * The online part of an ONLINE course. Boxed so that a partial update can leave a property out:
 * a primitive would read an absent property as 0 or false and overwrite the stored value.
 *
 * <p>{@code hasCertificate} and {@code dripEnabled} are always stored as false for now: nothing
 * issues certificates or releases lessons on a schedule yet, and a course must not promise either.
 */
public record OnlineDetailsDto(
        Integer totalVideoSeconds,
        Boolean hasCertificate,
        Boolean dripEnabled
) {}
