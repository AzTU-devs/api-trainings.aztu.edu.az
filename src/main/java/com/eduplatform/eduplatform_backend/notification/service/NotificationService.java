package com.eduplatform.eduplatform_backend.notification.service;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.notification.domain.Notification;
import com.eduplatform.eduplatform_backend.notification.repo.NotificationRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class NotificationService {

    private final NotificationRepository repo;

    public NotificationService(NotificationRepository repo) {
        this.repo = repo;
    }

    @Transactional(readOnly = true)
    public Page<Notification> list(UUID userId, Pageable pageable) {
        return repo.findAllByUserIdOrderByCreatedAtDesc(userId, pageable);
    }

    @Transactional(readOnly = true)
    public long unreadCount(UUID userId) {
        return repo.countByUserIdAndReadAtIsNull(userId);
    }

    @Transactional
    public void markRead(UUID userId, UUID notificationId) {
        Notification n = repo.findById(notificationId)
                .orElseThrow(() -> Errors.notFound("NOTIFICATION_NOT_FOUND", "Notification does not exist"));
        if (!n.getUser().getId().equals(userId)) {
            throw Errors.forbidden("NOTIFICATION_FORBIDDEN", "This notification does not belong to you");
        }
        if (n.getReadAt() == null) {
            n.setReadAt(Instant.now());
            repo.save(n);
        }
    }

    @Transactional
    public int markAllRead(UUID userId) {
        return repo.markAllReadForUser(userId, Instant.now());
    }
}
