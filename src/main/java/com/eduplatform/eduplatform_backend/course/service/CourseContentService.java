package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.CourseModule;
import com.eduplatform.eduplatform_backend.course.domain.Lesson;
import com.eduplatform.eduplatform_backend.course.repo.CourseModuleRepository;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.repo.LessonRepository;
import com.eduplatform.eduplatform_backend.course.service.CourseMediaValidator.MediaField;
import com.eduplatform.eduplatform_backend.course.web.dto.LessonUpsertRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.ModuleUpsertRequest;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
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
    private final CourseMediaValidator mediaValidator;

    public CourseContentService(CourseRepository courses, CourseModuleRepository modules,
                                LessonRepository lessons, CourseMediaValidator mediaValidator) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.mediaValidator = mediaValidator;
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
    public Lesson addLesson(AuthenticatedPrincipal caller, UUID moduleId, LessonUpsertRequest req) {
        CourseModule module = loadOwnedModule(caller.userId(), moduleId);
        Lesson l = Lesson.builder()
                .module(module)
                .title(req.title())
                .description(req.description())
                .contentType(req.contentType())
                .videoMedia(mediaValidator.resolve(
                        req.videoMediaId(), MediaField.lessonMaterial(req.contentType()), caller))
                .videoUrl(req.videoUrl())
                .durationSeconds(req.durationSeconds())
                .orderIndex(req.orderIndex())
                .preview(req.preview())
                .build();
        l.setId(UUID.randomUUID());
        return lessons.save(l);
    }

    @Transactional
    public Lesson updateLesson(AuthenticatedPrincipal caller, UUID lessonId, LessonUpsertRequest req) {
        Lesson l = loadOwnedLesson(caller.userId(), lessonId);
        // Before setContentType: whether the kind of file the lesson needs has changed is
        // judged against the content type it has now.
        l.setVideoMedia(materialForUpdate(l, req, caller));
        l.setTitle(req.title());
        l.setDescription(req.description());
        l.setContentType(req.contentType());
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

    /**
     * The file an updated lesson ends up with. The request is a full replacement, so a null id
     * removes the file.
     *
     * <p>An id equal to the lesson's current one is left alone rather than re-validated,
     * because the dashboard resends the lesson's current id with every save: re-checking would
     * fail an unrelated title edit whenever the file was attached before these checks existed,
     * or by a tutor the course has since been handed away from. When the content type changes
     * the kept file's kind is re-checked, though not its owner, since a lesson switched from
     * VIDEO to PDF would otherwise go on serving a video. Any other id is a new attachment and
     * gets every check.
     */
    private MediaFile materialForUpdate(Lesson lesson, LessonUpsertRequest req, AuthenticatedPrincipal caller) {
        MediaField field = MediaField.lessonMaterial(req.contentType());
        MediaFile current = lesson.getVideoMedia();
        // Reading the id off a lazy proxy does not load the row.
        if (req.videoMediaId() == null || current == null || !req.videoMediaId().equals(current.getId())) {
            return mediaValidator.resolve(req.videoMediaId(), field, caller);
        }
        if (req.contentType() != lesson.getContentType()) {
            mediaValidator.recheckKept(current, field);
        }
        return current;
    }

    private void requireOwner(Course course, UUID userId) {
        if (!course.getTutor().getUser().getId().equals(userId)) {
            throw Errors.forbidden("NOT_COURSE_OWNER", "Only the course owner can manage its content");
        }
    }
}
