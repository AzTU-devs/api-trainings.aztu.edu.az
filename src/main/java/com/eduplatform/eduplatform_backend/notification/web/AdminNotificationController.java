package com.eduplatform.eduplatform_backend.notification.web;

import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.notification.service.NotificationBroadcastService;
import com.eduplatform.eduplatform_backend.notification.web.dto.BroadcastRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/notifications")
@Tag(name = "Admin — Notifications")
@PreAuthorize("hasAuthority('notification:manage')")
public class AdminNotificationController {

    private final NotificationBroadcastService service;

    public AdminNotificationController(NotificationBroadcastService service) {
        this.service = service;
    }

    @PostMapping("/broadcast")
    @Operation(summary = "Broadcast an in-app notification to all users, a role, or specific users")
    public ApiResponse<Map<String, Integer>> broadcast(@Valid @RequestBody BroadcastRequest req) {
        return ApiResponse.ok(Map.of("recipients", service.broadcast(req)));
    }
}
