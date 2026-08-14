package com.eduplatform.eduplatform_backend.room.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.room.service.RoomService;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomDto;
import com.eduplatform.eduplatform_backend.room.web.dto.RoomUpsertRequest;
import com.eduplatform.eduplatform_backend.room.web.mapper.RoomMapper;
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
@RequestMapping("/api/admin/rooms")
@Tag(name = "Admin — Rooms")
@PreAuthorize("hasAuthority('room:manage')")
public class RoomAdminController {

    private final RoomService service;
    private final RoomMapper mapper;

    public RoomAdminController(RoomService service, RoomMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List rooms")
    public ApiResponse<PageResponse<RoomDto>> list(Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.list(pageable), mapper::toDto));
    }

    @PostMapping
    @Operation(summary = "Create a room")
    public ResponseEntity<ApiResponse<RoomDto>> create(@Valid @RequestBody RoomUpsertRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toDto(service.create(req))));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a room")
    public ApiResponse<RoomDto> update(@PathVariable UUID id, @Valid @RequestBody RoomUpsertRequest req) {
        return ApiResponse.ok(mapper.toDto(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a room")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
