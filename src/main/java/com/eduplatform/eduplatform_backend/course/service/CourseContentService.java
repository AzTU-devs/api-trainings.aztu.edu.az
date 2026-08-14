package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.CourseModule;
import com.eduplatform.eduplatform_backend.course.domain.Lesson;
import com.eduplatform.eduplatform_backend.course.repo.CourseModuleRepository;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.repo.LessonRepository;
import com.eduplatform.eduplatform_backend.course.web.dto.LessonUpsertRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.ModuleUpsertRequest;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Tutor-facing management of a course's content tree: modules and their lessons.
 * Every mutation verifies the acting user owns the parent course.
 */
@Service
public class CourseContentService {

    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final LessonRepository lessons;
    private final MediaFileRepository media;

    public CourseContentService(CourseRepository courses, CourseModuleRepository modules,
                                LessonRepository lessons, MediaFileRepository media) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.media = media;
    }

    // ---------------- modules ----------------

    @Transactional(readOnly = true)
    public List<CourseModule> listModules(UUID courseId) {
        List<CourseModule> list = modules.findAllByCourseIdOrderByOrderIndexAsc(courseId);
        list.forEach(m -> m.getLessons().size());   // init lazy lessons before the session closes
        return list;
    }

    @Transactional
    public CourseModule addModule(UUID userId, UUID courseId, ModuleUpsertRequest req) {
        Course course = loadOwnedCourse(userId, courseId);
        CourseModule m = CourseModule.builder()
                .course(course)
                .title(req.title())
                .description(req.description())
                .orderIndex(req.orderIndex())
                .build();
        m.setId(UUID.randomUUID());
        return modules.save(m);
    }

    @Transactional
    public CourseModule updateModule(UUID userId, UUID moduleId, ModuleUpsertRequest req) {
        CourseModule m = loadOwnedModule(userId, moduleId);
        m.setTitle(req.title());
        m.setDescription(req.description());
        m.setOrderIndex(req.orderIndex());
        CourseModule saved = modules.save(m);
        saved.getLessons().size();   // init lazy lessons for the response mapping
        return saved;
    }

    @Transactional
    public void deleteModule(UUID userId, UUID moduleId) {
        modules.delete(loadOwnedModule(userId, moduleId));   // soft-delete
    }

    // ---------------- lessons ----------------

    @Transactional(readOnly = true)
    public List<Lesson> listLessons(UUID moduleId) {
        return lessons.findAllByModuleIdOrderByOrderIndexAsc(moduleId);
    }

    @Transactional
    public Lesson addLesson(UUID userId, UUID moduleId, LessonUpsertRequest req) {
        CourseModule module = loadOwnedModule(userId, moduleId);
        Lesson l = Lesson.builder()
                .module(module)
                .title(req.title())
                .description(req.description())
                .contentType(req.contentType())
                .videoMedia(resolveMedia(req.videoMediaId()))
                .videoUrl(req.videoUrl())
                .durationSeconds(req.durationSeconds())
                .orderIndex(req.orderIndex())
                .preview(req.preview())
                .build();
        l.setId(UUID.randomUUID());
        return lessons.save(l);
    }

    @Transactional
    public Lesson updateLesson(UUID userId, UUID lessonId, LessonUpsertRequest req) {
        Lesson l = loadOwnedLesson(userId, lessonId);
        l.setTitle(req.title());
        l.setDescription(req.description());
        l.setContentType(req.contentType());
        l.setVideoMedia(resolveMedia(req.videoMediaId()));
        l.setVideoUrl(req.videoUrl());
        l.setDurationSeconds(req.durationSeconds());
        l.setOrderIndex(req.orderIndex());
        l.setPreview(req.preview());
        return lessons.save(l);
    }

    @Transactional
    public void deleteLesson(UUID userId, UUID lessonId) {
        lessons.delete(loadOwnedLesson(userId, lessonId));   // soft-delete
    }

    // ---------------- helpers ----------------

    private Course loadOwnedCourse(UUID userId, UUID courseId) {
        Course course = courses.findById(courseId)
                .orElseThrow(() -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
        requireOwner(course, userId);
        return course;
    }

    private CourseModule loadOwnedModule(UUID userId, UUID moduleId) {
        CourseModule m = modules.findById(moduleId)
                .orElseThrow(() -> Errors.notFound("MODULE_NOT_FOUND", "Module does not exist"));
        requireOwner(m.getCourse(), userId);
        return m;
    }

    private Lesson loadOwnedLesson(UUID userId, UUID lessonId) {
        Lesson l = lessons.findById(lessonId)
                .orElseThrow(() -> Errors.notFound("LESSON_NOT_FOUND", "Lesson does not exist"));
        requireOwner(l.getModule().getCourse(), userId);
        return l;
    }

    private MediaFile resolveMedia(UUID mediaId) {
        if (mediaId == null) return null;
        return media.findById(mediaId)
                .orElseThrow(() -> Errors.badRequest("INVALID_MEDIA", "Unknown media: " + mediaId));
    }

    private void requireOwner(Course course, UUID userId) {
        if (!course.getTutor().getUser().getId().equals(userId)) {
            throw Errors.forbidden("NOT_COURSE_OWNER", "Only the course owner can manage its content");
        }
    }
}
