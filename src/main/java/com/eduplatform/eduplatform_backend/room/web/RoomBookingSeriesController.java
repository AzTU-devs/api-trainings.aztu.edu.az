package com.eduplatform.eduplatform_backend.room.web;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.room.service.RoomBookingSeriesService;
import com.eduplatform.eduplatform_backend.room.web.dto.BookingSeriesCreateRequest;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomBookingSeriesDto;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/**
 * Long-term room bookings: one request for a room on chosen weekdays over a period, decided once.
 * The lists return each series without its occurrences; the detail returns all of them.
 */
@RestController
@RequestMapping("/api/portal/room-bookings/series")
@Tag(name = "Portal — Room booking series")
public class RoomBookingSeriesController {

    private final RoomBookingSeriesService service;

    public RoomBookingSeriesController(RoomBookingSeriesService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: request a room on chosen weekdays over a period")
    public ResponseEntity<ApiResponse<RoomBookingSeriesDto>> create(@Valid @RequestBody BookingSeriesCreateRequest req,
                                                                    @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(me.userId(), req)));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: list my booking series, newest first")
    public ApiResponse<PageResponse<RoomBookingSeriesDto>> mine(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listMine(me.userId(), pageable)));
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('room:approve')")
    @Operation(summary = "Admin: list booking series by status, oldest first")
    public ApiResponse<PageResponse<RoomBookingSeriesDto>> list(
            @RequestParam(defaultValue = "PENDING") BookingStatus status, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listByStatus(status, pageable)));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('room:book', 'room:approve')")
    @Operation(summary = "Tutor (own) or admin: a booking series with all its occurrences")
    public ApiResponse<RoomBookingSeriesDto> get(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(service.get(me, id));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: cancel one of my booking series (occurrences not yet started)")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        service.cancel(me.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/admin/{id}/decision")
    @PreAuthorize("hasAuthority('room:approve')")
    @Operation(summary = "Admin: approve or reject a booking series as a whole")
    public ApiResponse<RoomBookingSeriesDto> decide(@PathVariable UUID id,
                                                    @Valid @RequestBody ApprovalDecisionRequest req,
                                                    @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(service.decide(id, me.userId(), req.decision(), req.note()));
    }
}
