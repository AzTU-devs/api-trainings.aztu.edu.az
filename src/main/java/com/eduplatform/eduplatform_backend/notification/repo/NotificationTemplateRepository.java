package com.eduplatform.eduplatform_backend.notification.repo;

import com.eduplatform.eduplatform_backend.common.enums.NotificationChannel;
import com.eduplatform.eduplatform_backend.notification.domain.NotificationTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationTemplateRepository extends JpaRepository<NotificationTemplate, UUID> {

    Optional<NotificationTemplate> findByCodeAndChannelAndLocale(String code, NotificationChannel channel, String locale);
}
