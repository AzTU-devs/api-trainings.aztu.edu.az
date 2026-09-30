package com.eduplatform.eduplatform_backend.course.domain;

/**
 * One entry of a course's syllabus, as stored in the {@code courses.syllabus_items} JSONB array.
 * The array's order is the display order.
 *
 * @param title       plain text, trimmed, 1 to 200 characters
 * @param description rich-text HTML, already sanitised when it was written, or plain text; never
 *                    null, "" when there is none
 */
public record SyllabusItem(String title, String description) {}
