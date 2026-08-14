package com.eduplatform.eduplatform_backend.room.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.room.service.RoomService;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomDto;
import com.eduplatform.eduplatform_backend.room.web.mapper.RoomMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Tutor-facing room catalog — the rooms a tutor can browse and request to
 * borrow. Read-only; requires {@code room:read} (held by tutors). Only
 * AVAILABLE rooms are returned, with their images and hourly price.
 */
@RestController
@RequestMapping("/api/portal/rooms")
@Tag(name = "Portal — Rooms (tutor)")
@PreAuthorize("hasAuthority('room:read')")
public class RoomPortalController {

    private final RoomService service;
    private final RoomMapper mapper;

    public RoomPortalController(RoomService service, RoomMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List available rooms to borrow")
    public ApiResponse<PageResponse<RoomDto>> list(Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listAvailable(pageable), mapper::toDto));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a single room")
    public ApiResponse<RoomDto> get(@PathVariable UUID id) {
        return ApiResponse.ok(mapper.toDto(service.get(id)));
    }
}
