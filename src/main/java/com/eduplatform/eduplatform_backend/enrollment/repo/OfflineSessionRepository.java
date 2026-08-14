package com.eduplatform.eduplatform_backend.enrollment.repo;

import com.eduplatform.eduplatform_backend.enrollment.domain.OfflineSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface OfflineSessionRepository extends JpaRepository<OfflineSession, UUID> {

    List<OfflineSession> findAllByOfflineCourseCourseIdOrderByStartsAtAsc(UUID offlineCourseId);

    List<OfflineSession> findAllByOfflineCourseCourseIdAndSessionDateBetween(
            UUID offlineCourseId, LocalDate from, LocalDate to);
}
