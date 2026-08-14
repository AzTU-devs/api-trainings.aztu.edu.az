package com.eduplatform.eduplatform_backend.audit.web.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/** Runtime/system health snapshot for the super-admin monitoring view. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SystemHealthDto(
        long uptimeSeconds,
        double cpuUsagePct,
        long memoryUsedMb,
        long memoryTotalMb,
        long diskUsedGb,
        long diskTotalGb,
        String appVersion,
        String environment,
        String build,
        List<ServiceStatus> services,
        List<SystemIncident> recentIncidents
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ServiceStatus(String name, String state, Long latencyMs, String message) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SystemIncident(String id, String title, String severity, String state,
                                 String startedAt, String resolvedAt) {}
}
