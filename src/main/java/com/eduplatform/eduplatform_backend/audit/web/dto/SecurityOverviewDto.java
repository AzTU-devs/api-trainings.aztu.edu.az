package com.eduplatform.eduplatform_backend.audit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Aggregated security posture for the super-admin overview cards. */
public record SecurityOverviewDto(
        long failedLoginsLast24h,
        long lockedAccounts,
        long suspiciousLoginsLast24h,
        long blockedIps,
        List<SecurityEventDto> recentEvents,
        List<TopOffender> topOffendingIps
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopOffender(String ipAddress, long count, String countryCode) {}
}
