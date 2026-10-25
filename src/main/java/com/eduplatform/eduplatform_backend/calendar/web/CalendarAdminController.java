package com.eduplatform.eduplatform_backend.calendar.web;

import com.eduplatform.eduplatform_backend.calendar.service.CalendarService;
import com.eduplatform.eduplatform_backend.calendar.web.dto.CalendarDto;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/**
 * The super admin's calendar of everything taking place in person. Behind its own permission,
 * {@code calendar:view}, which V24 grants to SUPER_ADMIN only. Details open from the course
 * endpoints the dashboard already uses, so there is no event detail endpoint.
 */
@RestController
@RequestMapping("/api/admin/calendar")
@Tag(name = "Admin — Calendar")
public class CalendarAdminController {

    private final CalendarService service;

    public CalendarAdminController(CalendarService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('calendar:view')")
    @Operation(summary = "Every training, session and room booking over a window of local days",
            description = "from and to are local dates (app.timezone), to exclusive, at most 62 days apart "
                    + "(400 RANGE_TOO_LONG) and from before to (400 INVALID_DATE_RANGE). Events overlapping "
                    + "the window, sorted by start: ONE_TIME courses, OFFLINE courses' sessions (or their "
                    + "approved room bookings, or one all-day event over their dates), and approved or pending "
                    + "room bookings that belong to no course. ONLINE, ARCHIVED and deleted courses are left "
                    + "out. At most 2000 events; truncated says whether there were more.")
    public ApiResponse<CalendarDto> calendar(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.ok(service.calendar(from, to));
    }
}
