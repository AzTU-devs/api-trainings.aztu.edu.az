package com.eduplatform.eduplatform_backend.notification.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.notification.service.NotificationService;
import com.eduplatform.eduplatform_backend.notification.web.dto.NotificationDto;
import com.eduplatform.eduplatform_backend.notification.web.mapper.NotificationMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/portal/notifications")
@Tag(name = "Portal — Notifications")
@PreAuthorize("hasAuthority('notification:read_own')")
public class NotificationController {

    private final NotificationService service;
    private final NotificationMapper mapper;

    public NotificationController(NotificationService service, NotificationMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    @Operation(summary = "List my notifications")
    public ApiResponse<PageResponse<NotificationDto>> list(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.list(me.userId(), pageable), mapper::toDto));
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Unread notification count")
    public ApiResponse<Map<String, Long>> unreadCount(@CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(Map.of("count", service.unreadCount(me.userId())));
    }

    @PostMapping("/{id}/read")
    @Operation(summary = "Mark one notification as read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        service.markRead(me.userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    @Operation(summary = "Mark all my notifications as read")
    public ApiResponse<Map<String, Integer>> markAll(@CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(Map.of("updated", service.markAllRead(me.userId())));
    }
}
