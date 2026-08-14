package com.eduplatform.eduplatform_backend.enrollment.domain;

import com.eduplatform.eduplatform_backend.common.domain.BaseEntity;
import com.eduplatform.eduplatform_backend.common.enums.AttendanceStatus;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "attendance_records",
        uniqueConstraints = @UniqueConstraint(name = "uq_attendance_session_enrollment",
                columnNames = {"session_id", "enrollment_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AttendanceRecord extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "session_id", nullable = false)
    private OfflineSession session;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @Builder.Default
    private AttendanceStatus status = AttendanceStatus.ABSENT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "marked_by")
    private User markedBy;

    @Column(name = "marked_at", nullable = false)
    private Instant markedAt;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @PrePersist
    void onCreate() {
        if (markedAt == null) markedAt = Instant.now();
    }
}
