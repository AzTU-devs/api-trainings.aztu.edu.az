package com.eduplatform.eduplatform_backend.audit.service;

import com.eduplatform.eduplatform_backend.audit.domain.BlockedIp;
import com.eduplatform.eduplatform_backend.audit.repo.BlockedIpRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Maintains the IP blocklist; an in-memory mirror keeps per-request checks DB-free.
 *
 * <p>Addresses are compared in canonical form (see {@link #canonical}): the client address Tomcat
 * reports is canonical, and an entry stored as typed — "2001:db8::1" against Tomcat's
 * "2001:db8:0:0:0:0:0:1", say — would never have matched. Every change goes through this class,
 * which updates the table and the mirror together; a row deleted by hand in SQL stays enforced
 * until the next restart.
 */
@Service
public class BlockedIpService {

    private static final Logger log = LoggerFactory.getLogger(BlockedIpService.class);

    /** Dotted-quad IPv4. Anything else without a colon is not an address literal. */
    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** The characters an IPv6 literal (IPv4-mapped forms included) is made of. */
    private static final Pattern IPV6 = Pattern.compile("^[0-9A-Fa-f:.]+$");

    private final BlockedIpRepository repo;
    private final Set<String> blocked = ConcurrentHashMap.newKeySet();

    public BlockedIpService(BlockedIpRepository repo) {
        this.repo = repo;
    }

    @PostConstruct
    void warmCache() {
        try {
            repo.findAllIpStrings().forEach(ip -> blocked.add(canonical(ip).orElse(ip)));
        } catch (Exception ex) {
            log.warn("Could not preload blocked IP list (table may not exist yet)", ex);
        }
    }

    public boolean isBlocked(String ip) {
        return ip != null && !blocked.isEmpty() && blocked.contains(canonical(ip).orElse(ip));
    }

    /** @param ip already canonical; see {@link #canonical} */
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

    /** Lifts a block by its row; the mirror is cleared too, so it takes effect immediately. */
    @Transactional
    public Optional<BlockedIp> unblock(UUID id) {
        Optional<BlockedIp> row = repo.findById(id);
        row.ifPresent(b -> {
            repo.delete(b);
            blocked.remove(b.getIpAddress());
            blocked.remove(canonical(b.getIpAddress()).orElse(b.getIpAddress()));
        });
        return row;
    }

    @Transactional(readOnly = true)
    public java.util.List<BlockedIp> list() {
        return repo.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public long count() {
        return repo.count();
    }

    /**
     * The canonical text of a single IPv4 or IPv6 address literal, or empty when {@code raw} is not
     * one. Never a DNS lookup: InetAddress.getByName resolves anything it cannot parse as a
     * literal, so only strings shaped like a literal reach it — dotted-quad IPv4, or IPv6, which
     * always contains a colon and is then parsed or rejected, never looked up. A range ("/24")
     * is not an address and is refused here too.
     */
    public static Optional<String> canonical(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        boolean v4 = IPV4.matcher(s).matches();
        boolean v6 = s.indexOf(':') >= 0 && IPV6.matcher(s).matches();
        if (!v4 && !v6) return Optional.empty();
        if (v4) {
            for (String part : s.split("\\.")) {
                if (Integer.parseInt(part) > 255) return Optional.empty();
            }
        }
        try {
            return Optional.of(InetAddress.getByName(s).getHostAddress());
        } catch (UnknownHostException | SecurityException notALiteral) {
            return Optional.empty();
        }
    }
}
