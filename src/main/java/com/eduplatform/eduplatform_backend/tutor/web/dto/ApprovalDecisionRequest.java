package com.eduplatform.eduplatform_backend.tutor.web.dto;

import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ApprovalDecisionRequest(
        @NotNull BookingDecision decision,   // APPROVED | REJECTED — reused for tutor + room booking decisions
        @Size(max = 2000) String note
) {}
