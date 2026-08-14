package com.eduplatform.eduplatform_backend.course.domain;

import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.common.enums.LessonContentType;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "lessons",
        uniqueConstraints = @UniqueConstraint(name = "uq_lessons_module_order", columnNames = {"module_id", "order_index"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE lessons SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class Lesson extends SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private CourseModule module;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    @Builder.Default
    private LessonContentType contentType = LessonContentType.VIDEO;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_media_id")
    private MediaFile videoMedia;

    @Column(name = "video_url", length = 512)
    private String videoUrl;

    @Column(name = "duration_seconds", nullable = false)
    @Builder.Default
    private int durationSeconds = 0;

    @Column(name = "order_index", nullable = false)
    private int orderIndex;

    @Column(name = "is_preview", nullable = false)
    @Builder.Default
    private boolean preview = false;
}
