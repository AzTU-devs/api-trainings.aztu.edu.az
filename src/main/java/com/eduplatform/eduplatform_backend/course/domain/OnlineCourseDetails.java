package com.eduplatform.eduplatform_backend.course.domain;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

@Entity
@Table(name = "online_course_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OnlineCourseDetails {

    @Id
    @Column(name = "course_id", columnDefinition = "uuid")
    private UUID courseId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "total_video_seconds", nullable = false)
    @Builder.Default
    private int totalVideoSeconds = 0;

    @Column(name = "has_certificate", nullable = false)
    @Builder.Default
    private boolean hasCertificate = false;

    @Column(name = "drip_enabled", nullable = false)
    @Builder.Default
    private boolean dripEnabled = false;
}
