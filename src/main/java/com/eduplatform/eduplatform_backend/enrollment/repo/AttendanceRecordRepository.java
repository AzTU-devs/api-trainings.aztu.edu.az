package com.eduplatform.eduplatform_backend.enrollment.repo;

import com.eduplatform.eduplatform_backend.enrollment.domain.AttendanceRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

    List<AttendanceRecord> findAllBySessionId(UUID sessionId);

    List<AttendanceRecord> findAllByEnrollmentId(UUID enrollmentId);

    Optional<AttendanceRecord> findBySessionIdAndEnrollmentId(UUID sessionId, UUID enrollmentId);
}
