package com.eduplatform.eduplatform_backend.enrollment.domain;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class LessonProgressId implements Serializable {
    private UUID enrollmentId;
    private UUID lessonId;
}
