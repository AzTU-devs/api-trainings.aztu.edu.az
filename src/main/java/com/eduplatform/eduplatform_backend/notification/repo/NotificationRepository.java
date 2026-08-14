package com.eduplatform.eduplatform_backend.notification.repo;

import com.eduplatform.eduplatform_backend.common.enums.NotificationStatus;
import com.eduplatform.eduplatform_backend.notification.domain.Notification;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    long countByUserIdAndReadAtIsNull(UUID userId);

    List<Notification> findAllByStatus(NotificationStatus status);

    @Modifying
    @Query("update Notification n set n.readAt = :ts where n.user.id = :userId and n.readAt is null")
    int markAllReadForUser(@Param("userId") UUID userId, @Param("ts") Instant ts);
}
