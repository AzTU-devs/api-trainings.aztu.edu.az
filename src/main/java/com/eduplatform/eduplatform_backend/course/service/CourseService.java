package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.domain.Tag;
import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.catalog.repo.TagRepository;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.CourseStatus;
import com.eduplatform.eduplatform_backend.common.enums.CourseType;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.domain.Course;
import com.eduplatform.eduplatform_backend.course.domain.OfflineCourseDetails;
import com.eduplatform.eduplatform_backend.course.domain.OnlineCourseDetails;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.web.dto.AdminCreateCourseRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.CreateCourseRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.OfflineDetailsDto;
import com.eduplatform.eduplatform_backend.course.web.dto.OnlineDetailsDto;
import com.eduplatform.eduplatform_backend.course.web.dto.SetCourseTutorsRequest;
import com.eduplatform.eduplatform_backend.course.web.dto.UpdateCourseRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class CourseService {

    private final CourseRepository courses;
    private final TutorProfileRepository tutors;
    private final CategoryRepository categories;
    private final TagRepository tags;
    private final AuditService audit;

    public CourseService(CourseRepository courses, TutorProfileRepository tutors,
                         CategoryRepository categories, TagRepository tags, AuditService audit) {
        this.courses = courses;
        this.tutors = tutors;
        this.categories = categories;
        this.tags = tags;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Course get(UUID id) {
        Course c = courses.findById(id).orElseThrow(
                () -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
        initDetailGraph(c);
        return c;
    }

    @Transactional(readOnly = true)
    public Course getBySlug(String slug) {
        Course c = courses.findBySlug(slug).orElseThrow(
                () -> Errors.notFound("COURSE_NOT_FOUND", "Course does not exist"));
        initDetailGraph(c);
        return c;
    }

    @Transactional(readOnly = true)
    public Page<Course> browsePublished(CourseType type, UUID categoryId, Pageable pageable) {
        Page<Course> page = courses.browseCatalog(CourseStatus.PUBLISHED, type, categoryId, pageable);
        page.forEach(CourseService::initSummaryGraph);
        return page;
    }

    /** A tutor's own courses (drafts included), optionally filtered by status. */
    @Transactional(readOnly = true)
    public Page<Course> listMine(UUID userId, CourseStatus status, Pageable pageable) {
        Page<Course> page = status == null
                ? courses.findAllByTutorUser(userId, pageable)
                : courses.findAllByTutorUserAndStatus(userId, status, pageable);
        page.forEach(CourseService::initSummaryGraph);
        return page;
    }

    /** Admin moderation queue — courses in a given status (default IN_REVIEW), newest first. */
    @Transactional(readOnly = true)
    public Page<Course> listByStatus(CourseStatus status, Pageable pageable) {
        Page<Course> page = courses.findAllByStatusOrderByCreatedAtDesc(status, pageable);
        page.forEach(CourseService::initSummaryGraph);
        return page;
    }

    @Transactional(readOnly = true)
    public Page<Course> search(String query, Pageable pageable) {
        Page<Course> page = courses.search(query, pageable);
        page.forEach(CourseService::initSummaryGraph);
        return page;
    }

    /** Touch the lazy associations the summary mapper reads, before the session closes. */
    private static void initSummaryGraph(Course c) {
        if (c.getTutor() != null) {
            c.getTutor().getUser().getFirstName();   // init tutor + user for display name
        }
        // The teaching roster is mapped into BOTH the summary and detail DTOs, and the
        // mapper runs after the transaction closes — leaving it lazy throws
        // LazyInitializationException on every course response. @BatchSize(50) on the
        // association keeps this from N+1-ing across a page of courses.
        if (c.getTutors() != null) {
            c.getTutors().forEach(t -> {
                if (t.getUser() != null) t.getUser().getFirstName();
            });
        }
    }

    /** Touch every lazy association the detail mapper reads. @BatchSize keeps it from N+1-ing. */
    private static void initDetailGraph(Course c) {
        initSummaryGraph(c);
        c.getCategories().size();
        c.getTags().size();
        c.getModules().forEach(m -> m.getLessons().size());
    }

    @Transactional
    public Course createByTutor(UUID userId, CreateCourseRequest req) {
        TutorProfile tutor = tutors.findByUserId(userId)
                .orElseThrow(() -> Errors.forbidden("NOT_A_TUTOR", "Only tutors can create courses"));
        if (tutor.getApprovalStatus() != TutorApprovalStatus.APPROVED) {
            throw Errors.forbidden("TUTOR_NOT_APPROVED", "Tutor profile must be approved before creating courses");
        }
        if (courses.existsBySlug(req.slug())) {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Course slug '" + req.slug() + "' is taken");
        }
        validateTypeSpecific(req.courseType(), req.onlineDetails(), req.offlineDetails());

        Course course = Course.builder()
                .tutor(tutor)
                .slug(req.slug())
                .title(req.title())
                .subtitle(req.subtitle())
                .description(req.description())
                .requirements(req.requirements())
                .learningOutcomes(req.learningOutcomes())
                .syllabus(req.syllabus())
                .courseType(req.courseType())
                .level(req.level() == null ? com.eduplatform.eduplatform_backend.common.enums.CourseLevel.ALL : req.level())
                .language(req.language() == null ? "en" : req.language())
                .free(Boolean.TRUE.equals(req.free()))
                .price(req.price())
                .currency(req.currency())
                .status(CourseStatus.DRAFT)
                .categories(resolveCategories(req.categoryIds()))
                .tags(resolveTags(req.tagIds()))
                // A tutor creating their own course starts as the sole roster entry and
                // is the tutor authorised to edit it. Admins can widen the roster later.
                .tutors(new HashSet<>(Set.of(tutor)))
                .build();
        course.setId(UUID.randomUUID());

        attachTypeSpecific(course, req.courseType(), req.onlineDetails(), req.offlineDetails());
        Course saved = courses.save(course);
        // Initialize the lazy graph the mapper reads (tutor.user, categories, tags, modules)
        // while the session is still open — the controller maps to DTO after this returns.
        initDetailGraph(saved);
        return saved;
    }

    /**
     * Create a course on behalf of the university, assigning its tutors explicitly.
     *
     * <p>The tutor-facing {@link #createByTutor} derives the tutor from the caller,
     * which cannot work here: a super admin has no tutor profile. The course belongs
     * to the university either way — {@code tutor} only records who may edit it.
     */
    @Transactional
    public Course createByAdmin(AdminCreateCourseRequest req) {
        CreateCourseRequest c = req.course();
        if (courses.existsBySlug(c.slug())) {
            throw Errors.conflict("SLUG_ALREADY_EXISTS", "Course slug '" + c.slug() + "' is taken");
        }
        validateTypeSpecific(c.courseType(), c.onlineDetails(), c.offlineDetails());

        Set<TutorProfile> roster = resolveTutors(req.tutorIds());
        TutorProfile authorized = requireAuthorizedAmong(roster, req.authorizedTutorId());

        Course course = Course.builder()
                .tutor(authorized)
                .slug(c.slug())
                .title(c.title())
                .subtitle(c.subtitle())
                .description(c.description())
                .requirements(c.requirements())
                .learningOutcomes(c.learningOutcomes())
                .syllabus(c.syllabus())
                .courseType(c.courseType())
                .level(c.level() == null ? com.eduplatform.eduplatform_backend.common.enums.CourseLevel.ALL : c.level())
                .language(c.language() == null ? "en" : c.language())
                .free(Boolean.TRUE.equals(c.free()))
                .price(c.price())
                .currency(c.currency())
                .status(CourseStatus.DRAFT)
                .categories(resolveCategories(c.categoryIds()))
                .tags(resolveTags(c.tagIds()))
                .tutors(roster)
                .build();
        course.setId(UUID.randomUUID());

        attachTypeSpecific(course, c.courseType(), c.onlineDetails(), c.offlineDetails());
        Course saved = courses.save(course);
        audit.record(AuditService.Actions.CREATE, "COURSE", saved.getId(), null,
                AuditService.snapshot(
                        "createdBy", "ADMIN",
                        "tutorCount", roster.size(),
                        "authorizedTutorId", authorized.getId().toString()));
        initDetailGraph(saved);
        return saved;
    }

    /**
     * Replace a course's teaching roster and nominate the tutor authorised to edit it.
     * A full replacement, not a merge.
     */
    @Transactional
    public Course setTutors(UUID courseId, SetCourseTutorsRequest req) {
        Course course = get(courseId);
        Set<TutorProfile> roster = resolveTutors(req.tutorIds());
        TutorProfile authorized = requireAuthorizedAmong(roster, req.authorizedTutorId());
        String previousAuthorizedId =
                course.getTutor() == null ? null : course.getTutor().getId().toString();

        course.setTutors(roster);
        // Keep the two in step: the authorised editor must always be on the roster,
        // otherwise a course could be editable by someone who no longer teaches it.
        course.setTutor(authorized);

        Course saved = courses.save(course);
        audit.record(AuditService.Actions.UPDATE, "COURSE", courseId,
                AuditService.snapshot("authorizedTutorId", previousAuthorizedId),
                AuditService.snapshot(
                        "tutorCount", roster.size(),
                        "authorizedTutorId", authorized.getId().toString()));
        initDetailGraph(saved);
        return saved;
    }

    /** Loads every requested tutor, rejecting unknown or not-yet-approved ones. */
    private Set<TutorProfile> resolveTutors(Set<UUID> tutorIds) {
        Set<TutorProfile> resolved = new HashSet<>();
        for (UUID id : tutorIds) {
            TutorProfile t = tutors.findById(id).orElseThrow(() ->
                    Errors.notFound("TUTOR_NOT_FOUND", "Tutor profile " + id + " does not exist"));
            if (t.getApprovalStatus() != TutorApprovalStatus.APPROVED) {
                throw Errors.unprocessable("TUTOR_NOT_APPROVED",
                        "Tutor " + id + " is not approved and cannot be assigned to a course");
            }
            resolved.add(t);
        }
        return resolved;
    }

    private static TutorProfile requireAuthorizedAmong(Set<TutorProfile> roster, UUID authorizedTutorId) {
        return roster.stream()
                .filter(t -> t.getId().equals(authorizedTutorId))
                .findFirst()
                .orElseThrow(() -> Errors.unprocessable("AUTHORIZED_TUTOR_NOT_ON_ROSTER",
                        "The authorised tutor must be one of the tutors assigned to the course"));
    }

    @Transactional
    public Course updateByTutor(UUID userId, UUID courseId, UpdateCourseRequest req) {
        Course course = get(courseId);
        requireOwner(course, userId);

        if (req.title() != null)            course.setTitle(req.title());
        if (req.subtitle() != null)         course.setSubtitle(req.subtitle());
        if (req.description() != null)      course.setDescription(req.description());
        if (req.requirements() != null)     course.setRequirements(req.requirements());
        if (req.learningOutcomes() != null) course.setLearningOutcomes(req.learningOutcomes());
        if (req.syllabus() != null)         course.setSyllabus(req.syllabus());
        if (req.level() != null)            course.setLevel(req.level());
        if (req.language() != null)         course.setLanguage(req.language());
        if (req.free() != null)             course.setFree(req.free());
        if (req.price() != null)            course.setPrice(req.price());
        if (req.currency() != null)         course.setCurrency(req.currency());
        if (req.categoryIds() != null)      course.setCategories(resolveCategories(req.categoryIds()));
        if (req.tagIds() != null)           course.setTags(resolveTags(req.tagIds()));

        if (req.onlineDetails() != null && course.getCourseType() == CourseType.ONLINE) {
            mergeOnline(course, req.onlineDetails());
        }
        if (req.offlineDetails() != null && course.getCourseType() == CourseType.OFFLINE) {
            mergeOffline(course, req.offlineDetails());
        }
        return courses.save(course);
    }

    @Transactional
    public Course submitForReview(UUID userId, UUID courseId) {
        Course course = get(courseId);
        requireOwner(course, userId);
        if (course.getStatus() != CourseStatus.DRAFT && course.getStatus() != CourseStatus.REJECTED) {
            throw Errors.conflict("INVALID_COURSE_TRANSITION",
                    "Course can only be submitted for review from DRAFT or REJECTED");
        }
        course.setStatus(CourseStatus.IN_REVIEW);
        course.setRejectionReason(null);
        return courses.save(course);
    }

    @Transactional
    public Course adminDecide(UUID courseId, UUID adminId, BookingDecision decision, String note) {
        Course course = get(courseId);
        if (course.getStatus() != CourseStatus.IN_REVIEW) {
            throw Errors.conflict("INVALID_COURSE_TRANSITION",
                    "Course is not in review and cannot receive an approval decision");
        }
        if (decision == BookingDecision.APPROVED) {
            course.setStatus(CourseStatus.PUBLISHED);
            course.setApprovedAt(Instant.now());
            course.setApprovedBy(adminId);
            course.setPublishedAt(Instant.now());
            course.setRejectionReason(null);
        } else {
            course.setStatus(CourseStatus.REJECTED);
            course.setRejectionReason(note);
        }
        Course saved = courses.save(course);
        audit.record(
                decision == BookingDecision.APPROVED ? AuditService.Actions.PUBLISH : AuditService.Actions.REJECT,
                "COURSE", saved.getId(), null,
                AuditService.snapshot("status", saved.getStatus().name(), "note", note));
        return saved;
    }

    @Transactional
    public void archive(UUID userId, UUID courseId) {
        Course course = get(courseId);
        requireOwner(course, userId);
        course.setStatus(CourseStatus.ARCHIVED);
        courses.save(course);
    }

    // ---------- helpers ----------

    private void requireOwner(Course course, UUID userId) {
        if (!course.getTutor().getUser().getId().equals(userId)) {
            throw Errors.forbidden("NOT_COURSE_OWNER", "Only the course owner can perform this action");
        }
    }

    private void validateTypeSpecific(CourseType type, OnlineDetailsDto online, OfflineDetailsDto offline) {
        if (type == CourseType.ONLINE && offline != null) {
            throw Errors.badRequest("INVALID_DETAILS", "ONLINE course cannot have offline details");
        }
        if (type == CourseType.OFFLINE) {
            if (offline == null) {
                throw Errors.badRequest("OFFLINE_DETAILS_REQUIRED", "OFFLINE course requires offlineDetails");
            }
            if (offline.endDate().isBefore(offline.startDate())) {
                throw Errors.badRequest("INVALID_DATE_RANGE", "endDate must be on or after startDate");
            }
            if (offline.studentLimit() <= 0) {
                throw Errors.badRequest("INVALID_STUDENT_LIMIT", "studentLimit must be positive");
            }
        }
    }

    private void attachTypeSpecific(Course course, CourseType type, OnlineDetailsDto online, OfflineDetailsDto offline) {
        if (type == CourseType.ONLINE) {
            OnlineCourseDetails d = OnlineCourseDetails.builder()
                    .course(course)
                    .totalVideoSeconds(online == null ? 0 : online.totalVideoSeconds())
                    .hasCertificate(online != null && online.hasCertificate())
                    .dripEnabled(online != null && online.dripEnabled())
                    .build();
            course.setOnlineDetails(d);
        } else {
            OfflineCourseDetails d = OfflineCourseDetails.builder()
                    .course(course)
                    .startDate(offline.startDate())
                    .endDate(offline.endDate())
                    .weeklyHours(offline.weeklyHours())
                    .totalHours(offline.totalHours())
                    .studentLimit(offline.studentLimit())
                    .city(offline.city())
                    .addressLine(offline.addressLine())
                    .build();
            course.setOfflineDetails(d);
        }
    }

    private void mergeOnline(Course course, OnlineDetailsDto d) {
        OnlineCourseDetails od = course.getOnlineDetails();
        if (od == null) return;
        od.setTotalVideoSeconds(d.totalVideoSeconds());
        od.setHasCertificate(d.hasCertificate());
        od.setDripEnabled(d.dripEnabled());
    }

    private void mergeOffline(Course course, OfflineDetailsDto d) {
        OfflineCourseDetails od = course.getOfflineDetails();
        if (od == null) return;
        od.setStartDate(d.startDate());
        od.setEndDate(d.endDate());
        od.setWeeklyHours(d.weeklyHours());
        od.setTotalHours(d.totalHours());
        od.setStudentLimit(d.studentLimit());
        od.setCity(d.city());
        od.setAddressLine(d.addressLine());
    }

    private Set<Category> resolveCategories(Set<UUID> ids) {
        if (ids == null) return new HashSet<>();
        Set<Category> out = new HashSet<>();
        for (UUID id : ids) {
            out.add(categories.findById(id).orElseThrow(
                    () -> Errors.badRequest("INVALID_CATEGORY", "Unknown category: " + id)));
        }
        return out;
    }

    private Set<Tag> resolveTags(Set<UUID> ids) {
        if (ids == null) return new HashSet<>();
        Set<Tag> out = new HashSet<>();
        for (UUID id : ids) {
            out.add(tags.findById(id).orElseThrow(
                    () -> Errors.badRequest("INVALID_TAG", "Unknown tag: " + id)));
        }
        return out;
    }
}
