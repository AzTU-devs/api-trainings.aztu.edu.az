package com.eduplatform.eduplatform_backend.notification.web.mapper;

import com.eduplatform.eduplatform_backend.notification.domain.Notification;
import com.eduplatform.eduplatform_backend.notification.web.dto.NotificationDto;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface NotificationMapper {

    NotificationDto toDto(Notification n);
}
