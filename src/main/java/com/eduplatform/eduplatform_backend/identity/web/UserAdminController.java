package com.eduplatform.eduplatform_backend.identity.web;

import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.identity.service.UserAdminService;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserCreateRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserDto;
import com.eduplatform.eduplatform_backend.identity.web.dto.AdminUserUpdateRequest;
import com.eduplatform.eduplatform_backend.identity.web.dto.UserStatusUpdateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@Tag(name = "Admin — Users")
@PreAuthorize("hasAuthority('user:manage')")
public class UserAdminController {

    private final UserAdminService service;

    public UserAdminController(UserAdminService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List users (paged, filterable by search / role / status)")
    public ApiResponse<PageResponse<AdminUserDto>> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) RoleCode role,
            @RequestParam(required = false) String status,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        UserStatus statusFilter = parseStatusFilter(status);
        return ApiResponse.ok(PageResponse.of(service.list(search, role, statusFilter, pageable)));
    }

    @PostMapping
    @Operation(summary = "Create a user account")
    public ResponseEntity<ApiResponse<AdminUserDto>> create(@Valid @RequestBody AdminUserCreateRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update a user account")
    public ApiResponse<AdminUserDto> update(@PathVariable UUID id, @Valid @RequestBody AdminUserUpdateRequest req) {
        return ApiResponse.ok(service.update(id, req));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Enable / disable a user account")
    public ApiResponse<AdminUserDto> setStatus(@PathVariable UUID id, @Valid @RequestBody UserStatusUpdateRequest req) {
        return ApiResponse.ok(service.setStatus(id, req.status()));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Soft-delete a user account")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Maps the dashboard status filter to a backend status; unknown / blank → no filter. */
    private static UserStatus parseStatusFilter(String status) {
        if (status == null || status.isBlank()) return null;
        return switch (status.trim().toUpperCase()) {
            case "ACTIVE" -> UserStatus.ACTIVE;
            case "DISABLED" -> UserStatus.SUSPENDED;
            default -> null;
        };
    }
}
