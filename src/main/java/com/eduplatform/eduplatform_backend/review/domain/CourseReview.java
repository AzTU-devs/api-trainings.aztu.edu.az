package com.eduplatform.eduplatform_backend.review.domain;

import com.eduplatform.eduplatform_backend.common.domain.SoftDeletable;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.enrollment.domain.Enrollment;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Formula;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;

@Entity
@Table(name = "course_reviews",
        uniqueConstraints = @UniqueConstraint(name = "uq_course_review_course_user", columnNames = {"course_id", "user_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@SQLDelete(sql = "UPDATE course_reviews SET deleted_at = now() WHERE id = ? AND version = ?")
@SQLRestriction("deleted_at IS NULL")
public class CourseReview extends SoftDeletable {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    /**
     * The author's name as the review lists show it, read straight from the users row, and null
     * once the account is deleted. Going through {@link #user} meant loading the account, which
     * throws for a deleted one: hiding such a review from moderation failed with 404. Never
     * written.
     */
    @Formula("(select concat(u.first_name, ' ', u.last_name) from users u where u.id = user_id and u.deleted_at is null)")
    private String authorName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "enrollment_id")
    private Enrollment enrollment;

    @Column(name = "rating", nullable = false)
    private short rating;

    @Column(name = "title", length = 160)
    private String title;

    @Column(name = "body", columnDefinition = "text")
    private String body;

    @Column(name = "is_visible", nullable = false)
    @Builder.Default
    private boolean visible = true;
}
