package com.eduplatform.eduplatform_backend.notification.service;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.common.enums.UserStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.notification.web.dto.BroadcastRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/** Resolves a broadcast target audience and fans out an in-app notification to each recipient. */
@Service
public class NotificationBroadcastService {

    private final UserRepository users;
    private final NotificationDispatcher dispatcher;

    public NotificationBroadcastService(UserRepository users, NotificationDispatcher dispatcher) {
        this.users = users;
        this.dispatcher = dispatcher;
    }

    @Transactional
    public int broadcast(BroadcastRequest req) {
        List<User> recipients = switch (req.target()) {
            case ALL -> users.findAllByStatus(UserStatus.ACTIVE);
            case ROLE -> {
                if (req.role() == null) {
                    throw Errors.badRequest("ROLE_REQUIRED", "target=ROLE requires a role");
                }
                yield users.findAllByRoleAndStatus(req.role(), UserStatus.ACTIVE);
            }
            case USERS -> {
                if (req.userIds() == null || req.userIds().isEmpty()) {
                    throw Errors.badRequest("USER_IDS_REQUIRED", "target=USERS requires userIds");
                }
                yield users.findAllById(req.userIds());
            }
        };

        Map<String, Object> payload = Map.of("source", "admin-broadcast");
        for (User u : recipients) {
            dispatcher.dispatch(u, "admin.broadcast", req.title(), req.body(), payload, NotificationChannel.IN_APP);
        }
        return recipients.size();
    }
}
