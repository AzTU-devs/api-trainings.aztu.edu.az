package com.eduplatform.eduplatform_backend.audit.web;

import com.eduplatform.eduplatform_backend.audit.service.SecurityService;
import com.eduplatform.eduplatform_backend.audit.web.dto.BlockIpRequest;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityEventDto;
import com.eduplatform.eduplatform_backend.audit.web.dto.SecurityOverviewDto;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/super/security")
@Tag(name = "Super — Security")
@PreAuthorize("hasAuthority('security:manage')")
public class SecurityController {

    private final SecurityService service;

    public SecurityController(SecurityService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(summary = "Security posture overview")
    public ApiResponse<SecurityOverviewDto> overview() {
        return ApiResponse.ok(service.overview());
    }

    @GetMapping("/events")
    @Operation(summary = "List security events (filterable by kind / search)")
    public ApiResponse<PageResponse<SecurityEventDto>> events(
            @RequestParam(required = false) String kind,
            @RequestParam(required = false) String search,
            @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.listEvents(kind, search, pageable)));
    }

    @PostMapping("/block-ip")
    @Operation(summary = "Block an IP address from reaching the API")
    public ResponseEntity<Void> blockIp(@Valid @RequestBody BlockIpRequest req,
                                        @CurrentUser AuthenticatedPrincipal me,
                                        HttpServletRequest http) {
        service.blockIp(req.ipAddress(), req.reason(), me.userId(), http);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/unlock/{userId}")
    @Operation(summary = "Unlock a locked user account")
    public ResponseEntity<Void> unlock(@PathVariable UUID userId,
                                       @CurrentUser AuthenticatedPrincipal me,
                                       HttpServletRequest http) {
        service.unlockAccount(userId, me.userId(), http);
        return ResponseEntity.noContent().build();
    }
}
