package com.eduplatform.eduplatform_backend.tutor.repo;

import com.eduplatform.eduplatform_backend.common.enums.ApprovalStatus;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorApprovalRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface TutorApprovalRequestRepository extends JpaRepository<TutorApprovalRequest, UUID> {

    Page<TutorApprovalRequest> findAllByStatus(ApprovalStatus status, Pageable pageable);

    List<TutorApprovalRequest> findAllByTutorIdOrderBySubmittedAtDesc(UUID tutorId);
}
