package com.eduplatform.eduplatform_backend.tutor.repo;

import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface TutorProfileRepository extends JpaRepository<TutorProfile, UUID> {

    Optional<TutorProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    Page<TutorProfile> findAllByApprovalStatus(TutorApprovalStatus status, Pageable pageable);

    long countByApprovalStatus(TutorApprovalStatus status);
}
