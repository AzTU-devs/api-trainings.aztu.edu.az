package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
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
 * Management of a course's content tree: modules and their lessons.
 *
 * <p>Who may do what:
 * <ul>
 *   <li>Reading the tree — drafts included — is for staff and for anyone who teaches the course
 *       ({@link CourseAccess#mayManageContent}). Everybody else gets the 404 COURSE_NOT_FOUND the
 *       public slug endpoint gives for a course they may not see: any tutor used to be able to
 *       read another tutor's unpublished modules and lessons, file ids and links included.</li>
 *   <li>Changing it is for the course's authorised editor while their expert approval stands,
 *       and for staff, who author courses on the university's behalf and were locked out of the
 *       curriculum of the very courses they create. A staff edit to a course they are not the
 *       editor of is audited, since it changes someone else's work.</li>
 * </ul>
 */
@Service
public class CourseContentService {

    private final CourseRepository courses;
    private final CourseModuleRepository modules;
    private final LessonRepository lessons;
    private final CourseMediaValidator mediaValidator;
    private final AuditService audit;

    public CourseContentService(CourseRepository courses, CourseModuleRepository modules,
                                LessonRepository lessons, CourseMediaValidator mediaValidator,
                                AuditService audit) {
        this.courses = courses;
        this.modules = modules;
        this.lessons = lessons;
        this.mediaValidator = mediaValidator;
        this.audit = audit;
    }

    // ---------------- modules ----------------

    @Transactional(readOnly = true)
    public List<CourseModule> listModules(AuthenticatedPrincipal caller, UUID courseId) {
        Course course = courses.findById(courseId).orElseThrow(CourseContentService::courseNotFound);
        requireMayRead(course, caller);
        List<CourseModule> list = modules.findAllByCourseIdOrderByOrderIndexAsc(courseId);
        list.forEach(m -> m.getLessons().size());   // init lazy lessons before the session closes
        return list;
    }

    /**
     * Appends a module. Its position is assigned here, one past the highest the course has ever
     * used — deleted modules included — and the request's orderIndex is ignored. The dashboard sent
     * the number of live modules, which after any deletion collides with a live or a deleted
     * module's slot in the (course, order) unique index, so every later add failed with 409 and
     * the course could never grow again. The course row is locked first, so two adds at the same
     * moment cannot pick the same position.
     */
    @Transactional
    public CourseModule addModule(AuthenticatedPrincipal caller, UUID courseId, ModuleUpsertRequest req) {
        Course course = courses.findById(courseId).orElseThrow(CourseContentService::courseNotFound);
        requireMayEdit(course, caller);
        courses.lockForContentChange(courseId);
        CourseModule m = CourseModule.builder()
                .course(course)
                .title(req.title())
                .description(req.description())
                .orderIndex(modules.nextOrderIndex(courseId))
                .build();
        m.setId(UUID.randomUUID());
        CourseModule saved = modules.save(m);
        auditStaffEdit(course, caller, AuditService.Actions.CREATE, "COURSE_MODULE", saved.getId(), saved.getTitle());
        return saved;
    }

    /**
     * Edits a module, its position included. A position another module of the course holds is
     * refused with 409 ORDER_INDEX_TAKEN, before the write: it used to reach the (course, order)
     * unique index and come back as the generic CONSTRAINT_VIOLATION, which said nothing about
     * what to change. Moving to a free position works; swapping two goes through a free one.
     */
    @Transactional
    public CourseModule updateModule(AuthenticatedPrincipal caller, UUID moduleId, ModuleUpsertRequest req) {
        CourseModule m = loadEditableModule(caller, moduleId);
        if (req.orderIndex() != m.getOrderIndex()
                && modules.existsByCourseIdAndOrderIndexAndIdNot(m.getCourse().getId(), req.orderIndex(), m.getId())) {
            throw orderIndexTaken("module", req.orderIndex());
        }
        m.setTitle(req.title());
        m.setDescription(req.description());
        m.setOrderIndex(req.orderIndex());
        CourseModule saved = modules.save(m);
        saved.getLessons().size();   // init lazy lessons for the response mapping
        auditStaffEdit(saved.getCourse(), caller, AuditService.Actions.UPDATE, "COURSE_MODULE", saved.getId(),
                saved.getTitle());
        return saved;
    }

    @Transactional
    public void deleteModule(AuthenticatedPrincipal caller, UUID moduleId) {
        CourseModule m = loadEditableModule(caller, moduleId);
        modules.delete(m);   // soft-delete
        auditStaffEdit(m.getCourse(), caller, AuditService.Actions.DELETE, "COURSE_MODULE", m.getId(), m.getTitle());
    }

    // ---------------- lessons ----------------

    @Transactional(readOnly = true)
    public List<Lesson> listLessons(AuthenticatedPrincipal caller, UUID moduleId) {
        CourseModule module = modules.findById(moduleId).orElseThrow(CourseContentService::moduleNotFound);
        requireMayRead(module.getCourse(), caller);
        return lessons.findAllByModuleIdOrderByOrderIndexAsc(moduleId);
    }

    /** Appends a lesson; its position is assigned as a module's is (see {@link #addModule}). */
    @Transactional
    public Lesson addLesson(AuthenticatedPrincipal caller, UUID moduleId, LessonUpsertRequest req) {
        CourseModule module = loadEditableModule(caller, moduleId);
        modules.lockForContentChange(moduleId);
        Lesson l = Lesson.builder()
                .module(module)
                .title(req.title())
                .description(req.description())
                .contentType(req.contentType())
                .videoMedia(mediaValidator.resolve(
                        req.videoMediaId(), MediaField.lessonMaterial(req.contentType()), caller))
                .videoUrl(linkOrNull(req.videoUrl()))
                .durationSeconds(req.durationSeconds())
                .orderIndex(lessons.nextOrderIndex(moduleId))
                .preview(req.preview())
                .build();
        l.setId(UUID.randomUUID());
        Lesson saved = lessons.save(l);
        auditStaffEdit(module.getCourse(), caller, AuditService.Actions.CREATE, "LESSON", saved.getId(),
                saved.getTitle());
        return saved;
    }

    /** Edits a lesson; a position another lesson of the module holds is refused as for modules. */
    @Transactional
    public Lesson updateLesson(AuthenticatedPrincipal caller, UUID lessonId, LessonUpsertRequest req) {
        Lesson l = loadEditableLesson(caller, lessonId);
        if (req.orderIndex() != l.getOrderIndex()
                && lessons.existsByModuleIdAndOrderIndexAndIdNot(l.getModule().getId(), req.orderIndex(), l.getId())) {
            throw orderIndexTaken("lesson", req.orderIndex());
        }
        // Before setContentType: whether the kind of file the lesson needs has changed is
        // judged against the content type it has now.
        l.setVideoMedia(materialForUpdate(l, req, caller));
        l.setTitle(req.title());
        l.setDescription(req.description());
        l.setContentType(req.contentType());
        l.setVideoUrl(linkOrNull(req.videoUrl()));
        l.setDurationSeconds(req.durationSeconds());
        l.setOrderIndex(req.orderIndex());
        l.setPreview(req.preview());
        Lesson saved = lessons.save(l);
        auditStaffEdit(saved.getModule().getCourse(), caller, AuditService.Actions.UPDATE, "LESSON", saved.getId(),
                saved.getTitle());
        return saved;
    }

    @Transactional
    public void deleteLesson(AuthenticatedPrincipal caller, UUID lessonId) {
        Lesson l = loadEditableLesson(caller, lessonId);
        lessons.delete(l);   // soft-delete
        auditStaffEdit(l.getModule().getCourse(), caller, AuditService.Actions.DELETE, "LESSON", l.getId(),
                l.getTitle());
    }

    // ---------------- helpers ----------------

    private CourseModule loadEditableModule(AuthenticatedPrincipal caller, UUID moduleId) {
        CourseModule m = modules.findById(moduleId).orElseThrow(CourseContentService::moduleNotFound);
        requireMayEdit(m.getCourse(), caller);
        return m;
    }

    private Lesson loadEditableLesson(AuthenticatedPrincipal caller, UUID lessonId) {
        Lesson l = lessons.findById(lessonId)
                .orElseThrow(() -> Errors.notFound("LESSON_NOT_FOUND", "Lesson does not exist"));
        requireMayEdit(l.getModule().getCourse(), caller);
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

    /**
     * The link as kept: trimmed, and none for a blank one — the check on the request (http or
     * https) was made on the trimmed text, and a blank field is what an emptied form input sends.
     */
    private static String linkOrNull(String url) {
        return url == null || url.isBlank() ? null : url.trim();
    }

    /** A 404 rather than a 403, as the public slug endpoint answers: a 403 would confirm the draft. */
    private static void requireMayRead(Course course, AuthenticatedPrincipal caller) {
        if (!CourseAccess.mayManageContent(course, caller)) {
            throw courseNotFound();
        }
    }

    /**
     * Staff, or the authorised editor while their expert approval stands. The approval is checked
     * as well as the role because an access token issued before an approval was withdrawn still
     * carries course:update_own.
     */
    private static void requireMayEdit(Course course, AuthenticatedPrincipal caller) {
        if (CourseAccess.isStaff(caller)) return;
        if (!course.getTutor().getUser().getId().equals(caller.userId())) {
            throw Errors.forbidden("NOT_COURSE_OWNER", "Only the course owner can manage its content");
        }
        if (course.getTutor().getApprovalStatus() != TutorApprovalStatus.APPROVED) {
            throw Errors.forbidden("TUTOR_NOT_APPROVED", "Your expert profile is not approved");
        }
    }

    private void auditStaffEdit(Course course, AuthenticatedPrincipal caller, String action, String type,
                                UUID id, String title) {
        if (!CourseAccess.isStaff(caller) || course.getTutor().getUser().getId().equals(caller.userId())) return;
        audit.record(action, type, id, null,
                AuditService.snapshot("courseId", course.getId().toString(), "title", title));
    }

    private static com.eduplatform.eduplatform_backend.common.error.AppException courseNotFound() {
        return Errors.notFound("COURSE_NOT_FOUND", "Course does not exist");
    }

    /**
     * The same code and wording the exception handler gives the unique index's violation, so a
     * request that loses a race to a concurrent one reads the same as one that arrives second.
     */
    private static com.eduplatform.eduplatform_backend.common.error.AppException orderIndexTaken(String what,
                                                                                                int orderIndex) {
        return Errors.conflict("ORDER_INDEX_TAKEN", "Another " + what + " is already at position " + orderIndex
                + "; move that one to a free position first");
    }

    private static com.eduplatform.eduplatform_backend.common.error.AppException moduleNotFound() {
        return Errors.notFound("MODULE_NOT_FOUND", "Module does not exist");
    }
}
