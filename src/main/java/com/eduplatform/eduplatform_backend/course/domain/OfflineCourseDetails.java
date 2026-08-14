package com.eduplatform.eduplatform_backend.course.domain;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "offline_course_details")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OfflineCourseDetails {

    @Id
    @Column(name = "course_id", columnDefinition = "uuid")
    private UUID courseId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "weekly_hours", precision = 4, scale = 1)
    private BigDecimal weeklyHours;

    @Column(name = "total_hours", precision = 6, scale = 1)
    private BigDecimal totalHours;

    @Column(name = "student_limit", nullable = false)
    private int studentLimit;

    @Column(name = "enrolled_count", nullable = false)
    @Builder.Default
    private int enrolledCount = 0;

    @Column(name = "city", length = 80)
    private String city;

    @Column(name = "address_line", length = 255)
    private String addressLine;
}
