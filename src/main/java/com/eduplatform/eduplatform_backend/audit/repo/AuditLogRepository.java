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

    /**
     * The audit view's search. {@code search} matches what the page shows for each row — the
     * action, the resource type and id, the actor's email and name, and the client IP — so
     * searching for an administrator's address or a subnet finds their rows; it used to match the
     * action and resource type only. The actor is joined, not fetched: the row stores only its id,
     * and a deleted actor simply matches nothing by name.
     *
     * <p>The IP is compared through {@code host()}: the column is inet, which Postgres will not
     * lower-case or LIKE directly, and host() gives the bare address the page displays.
     */
    @Query(value = """
           select a from AuditLog a
             left join User u on u.id = a.actorId
           where (:action is null or a.action = :action)
             and (:actorId is null or a.actorId = :actorId)
             and (:entityType is null or a.entityType = :entityType)
             and (:search is null
                  or lower(a.action) like lower(concat('%', cast(:search as string), '%'))
                  or lower(a.entityType) like lower(concat('%', cast(:search as string), '%'))
                  or lower(cast(a.entityId as string)) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                  or lower(concat(u.firstName, ' ', u.lastName)) like lower(concat('%', cast(:search as string), '%'))
                  or cast(function('host', a.ipAddress) as String) like concat('%', cast(:search as string), '%'))
             and a.occurredAt >= :from
             and a.occurredAt <= :to
           order by a.occurredAt desc
           """,
           countQuery = """
           select count(a) from AuditLog a
             left join User u on u.id = a.actorId
           where (:action is null or a.action = :action)
             and (:actorId is null or a.actorId = :actorId)
             and (:entityType is null or a.entityType = :entityType)
             and (:search is null
                  or lower(a.action) like lower(concat('%', cast(:search as string), '%'))
                  or lower(a.entityType) like lower(concat('%', cast(:search as string), '%'))
                  or lower(cast(a.entityId as string)) like lower(concat('%', cast(:search as string), '%'))
                  or lower(u.email) like lower(concat('%', cast(:search as string), '%'))
                  or lower(concat(u.firstName, ' ', u.lastName)) like lower(concat('%', cast(:search as string), '%'))
                  or cast(function('host', a.ipAddress) as String) like concat('%', cast(:search as string), '%'))
             and a.occurredAt >= :from
             and a.occurredAt <= :to
           """)
    Page<AuditLog> search(@Param("search") String search,
                          @Param("action") String action,
                          @Param("actorId") UUID actorId,
                          @Param("entityType") String entityType,
                          @Param("from") Instant from,
                          @Param("to") Instant to,
                          Pageable pageable);
}
