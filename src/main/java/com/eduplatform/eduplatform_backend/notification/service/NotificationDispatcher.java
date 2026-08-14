package com.eduplatform.eduplatform_backend.notification.service;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.common.enums.NotificationStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.notification.domain.Notification;
import com.eduplatform.eduplatform_backend.notification.repo.NotificationRepository;
import com.eduplatform.eduplatform_backend.notification.web.dto.NotificationDto;
import com.eduplatform.eduplatform_backend.notification.web.mapper.NotificationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Persists a notification, then — after the transaction commits — pushes it to the
 * recipient's STOMP destination {@code /user/queue/notifications}.
 *
 * Other services should call {@link #dispatch} (do not write to {@code notifications} directly)
 * so the WebSocket fan-out stays consistent.
 */
@Service
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final String USER_QUEUE = "/queue/notifications";

    private final NotificationRepository repo;
    private final NotificationMapper mapper;
    private final SimpMessagingTemplate ws;
    private final ApplicationEventPublisher events;

    public NotificationDispatcher(NotificationRepository repo, NotificationMapper mapper,
                                  SimpMessagingTemplate ws, ApplicationEventPublisher events) {
        this.repo = repo;
        this.mapper = mapper;
        this.ws = ws;
        this.events = events;
    }

    @Transactional
    public Notification dispatch(User user, String templateCode, String title, String body,
                                 Map<String, Object> payload, NotificationChannel channel) {
        Notification n = Notification.builder()
                .user(user)
                .templateCode(templateCode)
                .channel(channel)
                .title(title)
                .body(body)
                .payload(payload)
                .status(NotificationStatus.PENDING)
                .build();
        n.setId(UUID.randomUUID());
        n = repo.save(n);

        // Fire only after commit so subscribers never see a row that was rolled back.
        events.publishEvent(new NotificationCommittedEvent(n.getId(), user.getId()));
        return n;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onCommitted(NotificationCommittedEvent ev) {
        Notification n = repo.findById(ev.notificationId()).orElse(null);
        if (n == null) return;

        if (n.getChannel() == NotificationChannel.IN_APP || n.getChannel() == NotificationChannel.WEBSOCKET) {
            NotificationDto dto = mapper.toDto(n);
            try {
                ws.convertAndSendToUser(ev.userId().toString(), USER_QUEUE, dto);
                n.setStatus(NotificationStatus.SENT);
                n.setSentAt(Instant.now());
                repo.save(n);
            } catch (Exception ex) {
                log.warn("WebSocket push failed for notification {} → user {}", n.getId(), ev.userId(), ex);
                n.setStatus(NotificationStatus.FAILED);
                repo.save(n);
            }
        }
        // EMAIL / SMS channels would dispatch through their own adapters here.
    }

    public record NotificationCommittedEvent(UUID notificationId, UUID userId) {}
}
