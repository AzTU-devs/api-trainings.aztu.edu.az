package com.eduplatform.eduplatform_backend.room.web;

import com.eduplatform.eduplatform_backend.common.enums.BookingStatus;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.room.service.RoomBookingService;
import com.eduplatform.eduplatform_backend.room.web.dto.BookingCreateRequest;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomBookingDto;
import com.eduplatform.eduplatform_backend.room.web.mapper.RoomMapper;
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

@RestController
@RequestMapping("/api/portal/room-bookings")
@Tag(name = "Portal — Room bookings")
public class RoomBookingController {

    private final RoomBookingService service;
    private final RoomMapper mapper;

    public RoomBookingController(RoomBookingService service, RoomMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: request a room booking")
    public ResponseEntity<ApiResponse<RoomBookingDto>> request(@Valid @RequestBody BookingCreateRequest req,
                                                               @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toBookingDto(service.requestBooking(me.userId(), req))));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: list my room bookings (history)")
    public ApiResponse<PageResponse<RoomBookingDto>> mine(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listMine(me.userId(), pageable), mapper::toBookingDto));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('room:book')")
    @Operation(summary = "Tutor: cancel one of my room bookings")
    public ResponseEntity<Void> cancel(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        service.cancel(me.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('room:approve')")
    @Operation(summary = "Admin: list bookings by status")
    public ApiResponse<PageResponse<RoomBookingDto>> list(
            @RequestParam(defaultValue = "PENDING") BookingStatus status,
            Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listByStatus(status, pageable), mapper::toBookingDto));
    }

    @PostMapping("/admin/{id}/decision")
    @PreAuthorize("hasAuthority('room:approve')")
    @Operation(summary = "Admin: approve or reject a booking")
    public ApiResponse<RoomBookingDto> decide(@PathVariable UUID id,
                                              @Valid @RequestBody ApprovalDecisionRequest req,
                                              @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(mapper.toBookingDto(service.decide(id, me.userId(), req.decision(), req.note())));
    }
}
