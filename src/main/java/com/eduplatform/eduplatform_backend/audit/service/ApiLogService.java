package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.ApiRequestLog;
import com.eduplatform.eduplatform_backend.audit.repo.ApiRequestLogRepository;
import com.eduplatform.eduplatform_backend.audit.web.dto.ApiLogEntryDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Persists and reads the raw HTTP API request log. */
@Service
public class ApiLogService {

    private static final Logger log = LoggerFactory.getLogger(ApiLogService.class);

    private final ApiRequestLogRepository repo;

    public ApiLogService(ApiRequestLogRepository repo) {
        this.repo = repo;
    }

    /** Best-effort async persistence from the access interceptor; never blocks or throws into the request path. */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ApiRequestLog row) {
        try {
            repo.save(row);
        } catch (Exception ex) {
            log.debug("Failed to persist API request log", ex);
        }
    }

    @Transactional
    public int purgeOlderThan(Instant cutoff) {
        return repo.deleteByOccurredAtBefore(cutoff);
    }

    private static final Instant MIN_TS = Instant.EPOCH;
    private static final Instant MAX_TS = Instant.parse("9999-12-31T23:59:59Z");

    @Transactional(readOnly = true)
    public Page<ApiLogEntryDto> list(String search, String method, Integer statusMin, Integer statusMax,
                                     boolean errorsOnly, Instant from, Instant to, Pageable pageable) {
        return repo.search(blank(search), blank(method), statusMin, statusMax, errorsOnly,
                from == null ? MIN_TS : from, to == null ? MAX_TS : to, pageable)
                .map(l -> new ApiLogEntryDto(
                        l.getId(), l.getMethod(), l.getPath(), l.getStatus(), l.getLatencyMs(),
                        l.getIpAddress(), l.getUserAgent(), l.getActorId(), l.getActorEmail(),
                        l.getRequestId(), l.getErrorMessage(), l.getResponseBytes(), l.getOccurredAt()));
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
