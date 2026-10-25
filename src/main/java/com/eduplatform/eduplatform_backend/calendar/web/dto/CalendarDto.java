package com.eduplatform.eduplatform_backend.calendar.web.dto;

import java.util.List;

/**
 * The calendar for a window of local days.
 *
 * @param timezone  the zone every local date and all-day boundary is in ({@code app.timezone})
 * @param events    sorted by start, then end
 * @param truncated true when the window held more events than the cap, and only the earliest
 *                  of them are listed; the dashboard then asks for a shorter window
 */
public record CalendarDto(String timezone, List<CalendarEventDto> events, boolean truncated) {}
