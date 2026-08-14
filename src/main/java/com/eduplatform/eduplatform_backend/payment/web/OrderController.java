package com.eduplatform.eduplatform_backend.payment.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.payment.service.OrderService;
import com.eduplatform.eduplatform_backend.payment.web.dto.OrderCreateRequest;
import com.eduplatform.eduplatform_backend.payment.web.dto.OrderDto;
import com.eduplatform.eduplatform_backend.payment.web.mapper.PaymentMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/portal/orders")
@Tag(name = "Portal — Orders")
public class OrderController {

    private final OrderService service;
    private final PaymentMapper mapper;

    public OrderController(OrderService service, PaymentMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('payment:checkout')")
    @Operation(summary = "Create a pending order; payment provider integration follows in Phase 8+")
    public ResponseEntity<ApiResponse<OrderDto>> create(@Valid @RequestBody OrderCreateRequest req,
                                                        @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(mapper.toOrderDto(service.create(me.userId(), req))));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasAuthority('payment:read_own')")
    @Operation(summary = "List my orders")
    public ApiResponse<PageResponse<OrderDto>> mine(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.mine(me.userId(), pageable), mapper::toOrderDto));
    }
}
