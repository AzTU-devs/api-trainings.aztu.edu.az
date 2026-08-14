package com.eduplatform.eduplatform_backend.audit.repo;

import com.eduplatform.eduplatform_backend.audit.domain.ApiRequestLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface ApiRequestLogRepository extends JpaRepository<ApiRequestLog, UUID> {

    @Modifying
    @Query("delete from ApiRequestLog l where l.occurredAt < :cutoff")
    int deleteByOccurredAtBefore(@Param("cutoff") Instant cutoff);

    @Query("""
           select l from ApiRequestLog l
           where (:search is null or lower(l.path) like lower(concat('%', cast(:search as string), '%')))
             and (:method is null or l.method = :method)
             and (:statusMin is null or l.status >= :statusMin)
             and (:statusMax is null or l.status <= :statusMax)
             and (:errorsOnly = false or l.status >= 400)
             and l.occurredAt >= :from
             and l.occurredAt <= :to
           order by l.occurredAt desc
           """)
    Page<ApiRequestLog> search(@Param("search") String search,
                               @Param("method") String method,
                               @Param("statusMin") Integer statusMin,
                               @Param("statusMax") Integer statusMax,
                               @Param("errorsOnly") boolean errorsOnly,
                               @Param("from") Instant from,
                               @Param("to") Instant to,
                               Pageable pageable);
}
