package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.BlockedIp;
import com.eduplatform.eduplatform_backend.audit.repo.BlockedIpRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Maintains the IP blocklist; an in-memory mirror keeps per-request checks DB-free. */
@Service
public class BlockedIpService {

    private static final Logger log = LoggerFactory.getLogger(BlockedIpService.class);

    private final BlockedIpRepository repo;
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();

    public BlockedIpService(BlockedIpRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void warmCache() {
        try {
            blocked.addAll(repo.findAllIpStrings());
        } catch (Exception ex) {
            log.warn("Could not preload blocked IP list (table may not exist yet)", ex);
        }
    }

    public boolean isBlocked(String ip) {
        return ip != null && blocked.contains(ip);
    }

    @Transactional
    public void block(String ip, String reason, UUID adminId) {
        if (!repo.existsByIpAddress(ip)) {
            BlockedIp b = BlockedIp.builder()
                    .id(UUID.randomUUID())
                    .ipAddress(ip)
                    .reason(reason)
                    .createdBy(adminId)
                    .createdAt(Instant.now())
                    .build();
            repo.save(b);
        }
        blocked.add(ip);
    }

    @Transactional
    public boolean unblock(String ip) {
        boolean removed = blocked.remove(ip);
        repo.findByIpAddress(ip).ifPresent(repo::delete);
        return removed;
    }

    @Transactional(readOnly = true)
    public long count() {
        return repo.count();
    }
}
