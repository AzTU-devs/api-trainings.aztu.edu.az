package com.eduplatform.eduplatform_backend.audit.repo;

import com.eduplatform.eduplatform_backend.audit.domain.SecurityEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface SecurityEventRepository extends JpaRepository<SecurityEvent, UUID> {

    Page<SecurityEvent> findAllByUserIdOrderByOccurredAtDesc(UUID userId, Pageable pageable);

    Page<SecurityEvent> findAllByEventTypeOrderByOccurredAtDesc(String eventType, Pageable pageable);

    long countByEventTypeAndOccurredAtAfter(String eventType, Instant since);

    @Query("""
           select e from SecurityEvent e
           where (:kind is null or e.eventType = :kind)
             and (:search is null or lower(e.eventType) like lower(concat('%', cast(:search as string), '%')))
           order by e.occurredAt desc
           """)
    Page<SecurityEvent> search(@Param("kind") String kind,
                               @Param("search") String search,
                               Pageable pageable);

    /** Top source IPs for a given event kind since a cutoff (busiest first). */
    @Query(value = """
           select host(ip_address) as ip, count(*) as cnt
           from security_events
           where event_type = :kind and occurred_at >= :since and ip_address is not null
           group by ip_address
           order by cnt desc
           """, nativeQuery = true)
    java.util.List<IpCountView> topOffenders(@Param("kind") String kind,
                                             @Param("since") Instant since,
                                             Pageable pageable);
}
