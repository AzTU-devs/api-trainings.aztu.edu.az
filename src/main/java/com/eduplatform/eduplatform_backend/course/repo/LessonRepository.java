package com.eduplatform.eduplatform_backend.course.repo;

import com.eduplatform.eduplatform_backend.course.domain.Lesson;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LessonRepository extends JpaRepository<Lesson, UUID> {

    List<Lesson> findAllByModuleIdOrderByOrderIndexAsc(UUID moduleId);

    /** Whether another live lesson of the module holds {@code orderIndex}; see CourseContentService. */
    boolean existsByModuleIdAndOrderIndexAndIdNot(UUID moduleId, int orderIndex, UUID id);

    /** Clears a deleted file from every lesson that used it; see VideoService.delete. */
    @Modifying
    @Query(value = "update lessons set video_media_id = null where video_media_id = :mediaId", nativeQuery = true)
    int detachMedia(@Param("mediaId") UUID mediaId);

    /** As CourseModuleRepository.nextOrderIndex, for a module's lessons. */
    @Query(value = "select coalesce(max(order_index), -1) + 1 from lessons where module_id = :moduleId",
            nativeQuery = true)
    int nextOrderIndex(@Param("moduleId") UUID moduleId);

    @Query("""
           select l from Lesson l
             join l.module m
           where m.course.id = :courseId
           order by m.orderIndex asc, l.orderIndex asc
           """)
    List<Lesson> findAllByCourseId(@Param("courseId") UUID courseId);

    @Query("""
           select count(l) from Lesson l
             join l.module m
           where m.course.id = :courseId
           """)
    long countByCourseId(@Param("courseId") UUID courseId);

    /**
     * True if {@code mediaId} is the file of a lesson in a course the user holds a place on, or
     * teaches. A place is an ACTIVE or COMPLETED enrolment: a participant who was removed keeps a
     * CANCELLED row (their progress is kept with it), and that row used to go on streaming every
     * lesson file. Co-tutors on the roster read the material they teach, not only the editor.
     */
    @Query("""
           select case when count(l) > 0 then true else false end
           from Lesson l
             join l.module m
           where l.videoMedia.id = :mediaId
             and (exists (select 1 from Enrollment e
                          where e.user.id = :userId and e.course.id = m.course.id
                            and e.status in (com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus.ACTIVE,
                                             com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus.COMPLETED))
                  or exists (select 1 from Course c left join c.tutors t
                             where c.id = m.course.id
                               and (c.tutor.user.id = :userId or t.user.id = :userId)))
           """)
    boolean isLessonMediaViewableBy(@Param("mediaId") UUID mediaId, @Param("userId") UUID userId);
}
