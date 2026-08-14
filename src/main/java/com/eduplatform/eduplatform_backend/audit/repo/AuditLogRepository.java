package com.eduplatform.eduplatform_backend.audit.repo;

import com.eduplatform.eduplatform_backend.audit.domain.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    Page<AuditLog> findAllByActorIdOrderByOccurredAtDesc(UUID actorId, Pageable pageable);

    Page<AuditLog> findAllByEntityTypeAndEntityIdOrderByOccurredAtDesc(String entityType, UUID entityId, Pageable pageable);

    Page<AuditLog> findAllByActionOrderByOccurredAtDesc(String action, Pageable pageable);

    @Query("""
           select a from AuditLog a
           where (:action is null or a.action = :action)
             and (:actorId is null or a.actorId = :actorId)
             and (:entityType is null or a.entityType = :entityType)
             and (:search is null
                  or lower(a.action) like lower(concat('%', cast(:search as string), '%'))
                  or lower(a.entityType) like lower(concat('%', cast(:search as string), '%')))
             and a.occurredAt >= :from
             and a.occurredAt <= :to
           order by a.occurredAt desc
           """)
    Page<AuditLog> search(@Param("search") String search,
                          @Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("entityType") String entityType,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
