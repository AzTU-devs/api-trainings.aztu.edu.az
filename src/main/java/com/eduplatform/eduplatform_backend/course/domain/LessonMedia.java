package com.eduplatform.eduplatform_backend.course.domain;

import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "lesson_media")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LessonMedia {

    @EmbeddedId
    private LessonMediaId id;

    @MapsId("lessonId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lesson_id")
    private Lesson lesson;

    @MapsId("mediaId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "media_id")
    private MediaFile media;
}
