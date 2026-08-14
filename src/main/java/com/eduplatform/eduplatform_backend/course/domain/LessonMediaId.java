package com.eduplatform.eduplatform_backend.course.domain;

import com.eduplatform.eduplatform_backend.common.enums.LessonMediaRole;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class LessonMediaId implements Serializable {

    private UUID lessonId;
    private UUID mediaId;

    @Enumerated(EnumType.STRING)
    private LessonMediaRole role;
}
