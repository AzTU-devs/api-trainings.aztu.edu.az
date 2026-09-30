package com.eduplatform.eduplatform_backend.tutor.service;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.common.enums.ApprovalStatus;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.enums.TokenRevokeReason;
import com.eduplatform.eduplatform_backend.common.enums.TutorApprovalStatus;
import com.eduplatform.eduplatform_backend.audit.service.AuditService;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.enrollment.repo.EnrollmentRepository;
import com.eduplatform.eduplatform_backend.identity.domain.Role;
import com.eduplatform.eduplatform_backend.identity.domain.User;
import com.eduplatform.eduplatform_backend.identity.domain.UserRole;
import com.eduplatform.eduplatform_backend.identity.domain.UserRoleId;
import com.eduplatform.eduplatform_backend.identity.repo.RoleRepository;
import com.eduplatform.eduplatform_backend.identity.repo.UserRepository;
import com.eduplatform.eduplatform_backend.identity.service.RefreshTokenService;
import com.eduplatform.eduplatform_backend.identity.service.UserSessionState;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.notification.service.DecisionEvents;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorApprovalRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorApprovalRequestRepository;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.HttpUrlValidator;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorApplyRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorStudentDto;
import com.eduplatform.eduplatform_backend.tutor.web.dto.UpdateTutorProfileRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.UpdateTutorProfileRequest.Field;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

@Service
public class TutorService {

    private final TutorProfileRepository profiles;
    private final TutorApprovalRequestRepository approvals;
    private final UserRepository users;
    private final RoleRepository roles;
    private final CategoryRepository categories;
    private final EnrollmentRepository enrollments;
    private final AuditService audit;
    private final TutorAvatarValidator avatars;
    private final RefreshTokenService refreshTokens;
    private final UserSessionState sessions;
    private final ApplicationEventPublisher events;

    public TutorService(TutorProfileRepository profiles, TutorApprovalRequestRepository approvals,
                        UserRepository users, RoleRepository roles, CategoryRepository categories,
                        EnrollmentRepository enrollments, AuditService audit, TutorAvatarValidator avatars,
                        RefreshTokenService refreshTokens, UserSessionState sessions,
                        ApplicationEventPublisher events) {
        this.profiles = profiles;
        this.approvals = approvals;
        this.users = users;
        this.roles = roles;
        this.categories = categories;
        this.enrollments = enrollments;
        this.audit = audit;
        this.avatars = avatars;
        this.refreshTokens = refreshTokens;
        this.sessions = sessions;
        this.events = events;
    }

    /** Students enrolled in the current tutor's courses, aggregated per student. */
    @Transactional(readOnly = true)
    public Page<TutorStudentDto> listMyStudents(UUID tutorUserId, String search, UUID courseId, Pageable pageable) {
        String s = (search == null || search.isBlank()) ? null : search.trim();
        return enrollments.findTutorStudents(tutorUserId, s, courseId, EnrollmentStatus.ACTIVE, pageable)
                .map(r -> new TutorStudentDto(
                        r.getUserId(),
                        (r.getFirstName() + " " + (r.getLastName() == null ? "" : r.getLastName())).trim(),
                        r.getEmail(),
                        r.getAvatarId() == null ? null : "/api/media/" + r.getAvatarId() + "/content",
                        r.getActiveEnrollments(),
                        r.getTotalEnrollments(),
                        r.getAverageProgressPct() == null ? 0.0 : r.getAverageProgressPct(),
                        r.getLastActivityAt()));
    }

    /**
     * Files an expert application from an existing account (the portal's apply endpoint): a PENDING
     * profile plus the approval request moderators work from. The applicant has to name at least
     * one area of expertise, a category or one of their own (400 EXPERTISE_REQUIRED).
     */
    @Transactional
    public TutorProfile apply(UUID userId, TutorApplyRequest req) {
        return fileApplication(userId, req, true);
    }

    /**
     * The same application, filed by the OTP sign-up's verify step, which rebuilds the request from
     * the row stored at the start step. The areas were held to the rules there. A category an admin
     * hid in the minutes since has already been dropped by the caller, and the application goes in
     * even if that leaves no area at all: refusing it now, after the code was proven, would lose a
     * sign-up the applicant can no longer correct.
     */
    @Transactional
    public TutorProfile applyFromSignup(UUID userId, TutorApplyRequest req) {
        return fileApplication(userId, req, false);
    }

    /**
     * A row stored before sign-up validated links and years could hold values no later profile save
     * would accept, so those are dropped here rather than stored and served.
     */
    private TutorProfile fileApplication(UUID userId, TutorApplyRequest req, boolean requireAnArea) {
        if (profiles.existsByUserId(userId)) {
            throw Errors.conflict("TUTOR_PROFILE_EXISTS",
                    "You already have an expert profile; edit it, or resubmit it if it was rejected");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));

        Set<Category> expertise = resolveCategories(
                req.categoryIds() == null ? Set.of() : req.categoryIds(), Set.of());
        List<String> customExpertise = CustomExpertise.normalize(req.customExpertise(), namesOf(expertise));
        if (requireAnArea) {
            CustomExpertise.requireAny(expertise, customExpertise);
        }

        TutorProfile profile = TutorProfile.builder()
                .user(user)
                .headline(trimToNull(req.headline()))
                .bio(trimToNull(req.bio()))
                .yearsExperience(plausibleYears(req.yearsExperience()))
                .websiteUrl(webAddressOrNull(req.websiteUrl()))
                .linkedinUrl(webAddressOrNull(req.linkedinUrl()))
                .approvalStatus(TutorApprovalStatus.PENDING)
                .expertises(expertise)
                .customExpertise(customExpertise)
                .build();
        profile.setId(UUID.randomUUID());
        profile = profiles.save(profile);

        TutorApprovalRequest reqRow = TutorApprovalRequest.builder()
                .tutor(profile)
                .status(ApprovalStatus.PENDING)
                .submittedAt(Instant.now())
                .build();
        reqRow.setId(UUID.randomUUID());
        approvals.save(reqRow);

        return profile;
    }

    /**
     * Gives an account an approved expert profile when an administrator grants it the TUTOR
     * role from the Users page. Everything an expert does — creating a course, appearing on the
     * Tutors page and in the roster pickers — is keyed on the profile, not the role, so a TUTOR
     * without one was a dead end: it could sign in to the teaching area and do nothing there, and
     * the administrators had no action that would create the profile. The profile starts empty;
     * the Tutors page's Edit dialog fills in the headline, biography and photo.
     *
     * <p>Does nothing when the account already has a profile, whatever its status: an applicant
     * keeps the application they filed, and its decision stays with the approval queue.
     */
    @Transactional
    public void ensureApprovedProfile(User user, UUID adminId) {
        if (profiles.existsByUserId(user.getId())) return;
        Instant now = Instant.now();
        TutorProfile profile = TutorProfile.builder()
                .user(user)
                .approvalStatus(TutorApprovalStatus.APPROVED)
                .approvedAt(now)
                .approvedBy(adminId)
                .expertises(new HashSet<>())
                .build();
        profile.setId(UUID.randomUUID());
        profile = profiles.save(profile);

        TutorApprovalRequest request = TutorApprovalRequest.builder()
                .tutor(profile)
                .status(ApprovalStatus.APPROVED)
                .decisionNote("Granted by administrator")
                .decidedBy(users.getReferenceById(adminId))
                .decidedAt(now)
                .submittedAt(now)
                .build();
        request.setId(UUID.randomUUID());
        approvals.save(request);
        audit.record(AuditService.Actions.APPROVE, "TUTOR_PROFILE", profile.getId(), null,
                AuditService.snapshot("status", TutorApprovalStatus.APPROVED.name(), "source", "ROLE_GRANT"));
    }

    /**
     * Retires the expert profile of an account that is about to be deleted, or refuses the
     * deletion. A course or a room booking that still names the profile would, once the account
     * is gone, point at a user no read can load: the course lists, the catalogue and the Tutors
     * page all failed with 500 until the row was restored by hand. So a profile that anything
     * references blocks the deletion with 409, and one that nothing references is soft-deleted
     * with the account, so the Tutors page does not list an expert without an account.
     */
    @Transactional
    public void retireProfileOf(UUID userId) {
        TutorProfile profile = profiles.findByUserId(userId).orElse(null);
        if (profile == null) return;
        if (profiles.isNamedOnAnyCourse(profile.getId())) {
            throw Errors.conflict("USER_HAS_COURSES",
                    "This expert is named on one or more trainings. Reassign those trainings to another "
                            + "expert first, or disable the account instead of deleting it.");
        }
        if (profiles.hasRoomBookings(profile.getId())) {
            throw Errors.conflict("USER_HAS_ROOM_BOOKINGS",
                    "This expert has room bookings on record. Disable the account instead of deleting it.");
        }
        profiles.delete(profile);
    }

    /**
     * Public, read-only view of an APPROVED tutor profile. A profile whose account is gone is not
     * an expert anyone can meet any more, and it answers as one that does not exist.
     */
    @Transactional(readOnly = true)
    public TutorProfile publicProfile(UUID tutorId) {
        TutorProfile p = profiles.findWithLiveAccountById(tutorId)
                .orElseThrow(() -> Errors.notFound("TUTOR_NOT_FOUND", "Tutor does not exist"));
        if (p.getApprovalStatus() != TutorApprovalStatus.APPROVED) {
            throw Errors.notFound("TUTOR_NOT_FOUND", "Tutor does not exist");
        }
        initForMapping(p);
        return p;
    }

    @Transactional(readOnly = true)
    public TutorProfile myProfile(UUID userId) {
        TutorProfile p = profiles.findByUserId(userId)
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND",
                        "You have not applied to become a tutor yet"));
        initForMapping(p);
        return p;
    }

    /** Largest page the public directory hands out, the same cap as spring.data.web.pageable. */
    private static final int PUBLIC_PAGE_MAX = 100;

    /** The directory's one order: by name, then id, so a page boundary never splits a tie. */
    private static final Sort PUBLIC_ORDER = Sort.by("displayName").ascending().and(Sort.by("id"));

    /**
     * The public expert directory: every approved expert whose account still exists, whether or
     * not they have a published course yet. The order is fixed here, never taken from the caller
     * (see TutorPublicController#list).
     */
    @Transactional(readOnly = true)
    public Page<TutorProfile> listPublic(int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), PUBLIC_PAGE_MAX), PUBLIC_ORDER);
        return listByStatus(TutorApprovalStatus.APPROVED, pageable);
    }

    /** The Tutors page's tabs: experts whose account still exists (see TutorProfileRepository). */
    @Transactional(readOnly = true)
    public Page<TutorProfile> listByStatus(TutorApprovalStatus status, Pageable pageable) {
        Page<TutorProfile> page = profiles.findAllWithLiveAccountByApprovalStatus(status, pageable);
        page.forEach(TutorService::initForMapping);
        return page;
    }

    /**
     * A rejected expert edits their application and puts it back in the approval queue.
     *
     * <p>Without this a rejection was final: the TUTOR-only permissions to edit or apply were out
     * of reach (a rejected applicant holds only USER), applying again answered "profile exists",
     * and signing up again answered "email already registered". Only a REJECTED profile can be
     * resubmitted; a pending one is already in the queue and an approved one has nothing to ask.
     */
    @Transactional
    public TutorProfile resubmit(AuthenticatedPrincipal caller, UpdateTutorProfileRequest req) {
        TutorProfile profile = profiles.findByUserId(caller.userId())
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND",
                        "You have not applied to become an expert yet"));
        if (profile.getApprovalStatus() != TutorApprovalStatus.REJECTED) {
            throw Errors.conflict("TUTOR_NOT_REJECTED",
                    "Only a rejected application can be resubmitted; this one is "
                            + profile.getApprovalStatus());
        }
        TutorProfile saved = updateProfile(caller, profile, req);
        saved.setApprovalStatus(TutorApprovalStatus.PENDING);
        saved.setRejectionReason(null);
        saved.setApprovedAt(null);
        saved.setApprovedBy(null);
        saved = profiles.save(saved);

        TutorApprovalRequest request = TutorApprovalRequest.builder()
                .tutor(saved)
                .status(ApprovalStatus.PENDING)
                .submittedAt(Instant.now())
                .build();
        request.setId(UUID.randomUUID());
        approvals.save(request);
        audit.record(AuditService.Actions.UPDATE, "TUTOR_PROFILE", saved.getId(),
                AuditService.snapshot("status", TutorApprovalStatus.REJECTED.name()),
                AuditService.snapshot("status", TutorApprovalStatus.PENDING.name(), "resubmitted", true));
        initForMapping(saved);
        return saved;
    }

    /**
     * The expert editing their own profile. Applying is what creates a profile, so there has to be
     * one already.
     */
    @Transactional
    public TutorProfile updateOwnProfile(AuthenticatedPrincipal caller, UpdateTutorProfileRequest req) {
        TutorProfile profile = profiles.findByUserId(caller.userId())
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND",
                        "You have not applied to become a tutor yet"));
        return updateProfile(caller, profile, req);
    }

    /**
     * An admin editing any expert's profile, whatever its approval status — any expert who still
     * has an account: the profile of a deleted one is kept only for the courses that name it.
     */
    @Transactional
    public TutorProfile updateProfileByAdmin(AuthenticatedPrincipal caller, UUID tutorId,
                                             UpdateTutorProfileRequest req) {
        TutorProfile profile = profiles.findWithLiveAccountById(tutorId)
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND", "Tutor does not exist"));
        return updateProfile(caller, profile, req);
    }

    /**
     * The one profile edit behind both entry points, so the expert and an admin are held to the
     * same rules. Only the lookup differs; even the avatar rule needs no branch, since "the
     * expert or whoever is editing" is just the expert when they edit themselves.
     *
     * <p>Approval is never touched: going live is decided by {@link #decide} alone, and an
     * approved expert's edits are public straight away, like a tutor's edits to a live course.
     * Both edits are audited, because what is published under an expert's name has to be
     * attributable whoever wrote it.
     */
    private TutorProfile updateProfile(AuthenticatedPrincipal caller, TutorProfile p, UpdateTutorProfileRequest req) {
        // The @Version on the entity only protects one transaction against another; the version
        // the client read is what catches an admin and the expert editing the same profile.
        if (req.getVersion() != null && req.getVersion() != p.getVersion()) {
            throw new ObjectOptimisticLockingFailureException(TutorProfile.class, p.getId());
        }
        Map<String, Object> before = auditableFields(p);

        if (req.has(Field.AVATAR_MEDIA_ID)) {
            UUID requested = req.getAvatarMediaId();
            if (requested == null) {
                p.setAvatar(null);
            } else if (!requested.equals(idOf(p.getAvatar()))) {
                // An id equal to the current one is left alone rather than re-checked: the dashboard
                // resends it with every save, and re-checking would fail an expert's unrelated edit
                // whenever the portrait was one an admin uploaded for them.
                p.setAvatar(avatars.resolve(requested, p, caller));
            }
        }
        if (req.has(Field.YEARS_EXPERIENCE)) p.setYearsExperience(req.getYearsExperience());

        patchText(req, Field.HEADLINE,           req.getHeadline(),         p::setHeadline);
        patchText(req, Field.BIO,                req.getBio(),              p::setBio);
        patchText(req, Field.ACADEMIC_TITLE,     req.getAcademicTitle(),    p::setAcademicTitle);
        patchText(req, Field.DEPARTMENT,         req.getDepartment(),       p::setDepartment);
        patchText(req, Field.EDUCATION,          req.getEducation(),        p::setEducation);
        patchText(req, Field.CERTIFICATIONS,     req.getCertifications(),   p::setCertifications);
        patchText(req, Field.LANGUAGES,          req.getLanguages(),        p::setLanguages);
        patchText(req, Field.WEBSITE_URL,        req.getWebsiteUrl(),       p::setWebsiteUrl);
        patchText(req, Field.LINKEDIN_URL,       req.getLinkedinUrl(),      p::setLinkedinUrl);
        patchText(req, Field.GOOGLE_SCHOLAR_URL, req.getGoogleScholarUrl(), p::setGoogleScholarUrl);
        patchText(req, Field.RESEARCH_GATE_URL,  req.getResearchGateUrl(),  p::setResearchGateUrl);
        patchText(req, Field.GITHUB_URL,         req.getGithubUrl(),        p::setGithubUrl);
        // The iD's check digit is an upper-case X by definition; a typed lower-case one is the same iD.
        patchText(req, Field.ORCID, req.getOrcid(),
                orcid -> p.setOrcid(orcid == null ? null : orcid.toUpperCase(Locale.ROOT)));

        if (req.getExpertiseCategoryIds() != null) {
            p.setExpertises(resolveCategories(req.getExpertiseCategoryIds(), p.getExpertises()));
        }
        // Checked only when the edit touches the areas: a profile an administrator created by
        // granting the TUTOR role starts with none, and editing its headline must still work. The
        // custom labels are re-normalised even when only the categories were sent, so one that now
        // repeats a newly picked category's name goes.
        if (req.getExpertiseCategoryIds() != null || req.getCustomExpertise() != null) {
            List<String> customExpertise = CustomExpertise.normalize(
                    req.getCustomExpertise() != null ? req.getCustomExpertise() : p.getCustomExpertise(),
                    namesOf(p.getExpertises()));
            CustomExpertise.requireAny(p.getExpertises(), customExpertise);
            p.setCustomExpertise(customExpertise);
        }

        // Continue with what save() returns: with a hand-assigned id it merges, and anything
        // done to the instance passed in afterwards would not be the persisted state.
        TutorProfile saved = profiles.save(p);
        audit.record(AuditService.Actions.UPDATE, "TUTOR_PROFILE", saved.getId(), before, auditableFields(saved));
        initForMapping(saved);
        return saved;
    }

    /**
     * Applies one text property of a merge patch: absent leaves the value, and null or blank
     * clears it — blank because that is what an emptied form input sends, and storing it would
     * leave the public page a heading with nothing under it. Anything else is kept trimmed.
     */
    private static void patchText(UpdateTutorProfileRequest req, Field field, String value, Consumer<String> setter) {
        if (!req.has(field)) return;
        setter.accept(value == null || value.isBlank() ? null : value.trim());
    }

    /**
     * The categories named by {@code ids}, under the rule courses follow (CourseService): a
     * category an admin has hidden cannot be newly assigned, because the public expert page would
     * then list an area the catalogue does not show, and the hidden category could no longer be
     * deleted while the profile held it. One the profile already has is kept, so hiding a
     * category does not make every expert filed under it unable to save their profile.
     */
    private Set<Category> resolveCategories(Set<UUID> ids, Set<Category> current) {
        Set<UUID> kept = new HashSet<>();
        current.forEach(c -> kept.add(c.getId()));
        Set<Category> resolved = new HashSet<>();
        for (UUID catId : ids) {
            // A null id would reach findById, which rejects it with a server error, not a 400.
            if (catId == null) {
                throw Errors.badRequest("INVALID_CATEGORY", "Unknown category: null");
            }
            Category category = categories.findById(catId)
                    .orElseThrow(() -> Errors.badRequest("INVALID_CATEGORY", "Unknown category: " + catId));
            if (!category.isActive() && !kept.contains(catId)) {
                throw Errors.badRequest("CATEGORY_INACTIVE", "Category " + category.getName() + " is hidden");
            }
            resolved.add(category);
        }
        return resolved;
    }

    /**
     * Everything an edit can change, so the audit diff shows exactly what moved. AuditService
     * drops the entries that did not, so the long free-text fields cost nothing unless edited.
     * Ids are strings, and the areas a sorted list, so equal values always compare equal.
     */
    private static Map<String, Object> auditableFields(TutorProfile p) {
        UUID avatarId = idOf(p.getAvatar());
        return AuditService.snapshot(
                "headline", p.getHeadline(),
                "bio", p.getBio(),
                "yearsExperience", p.getYearsExperience(),
                "avatarMediaId", avatarId == null ? null : avatarId.toString(),
                "academicTitle", p.getAcademicTitle(),
                "department", p.getDepartment(),
                "education", p.getEducation(),
                "certifications", p.getCertifications(),
                "languages", p.getLanguages(),
                "websiteUrl", p.getWebsiteUrl(),
                "linkedinUrl", p.getLinkedinUrl(),
                "googleScholarUrl", p.getGoogleScholarUrl(),
                "researchGateUrl", p.getResearchGateUrl(),
                "orcid", p.getOrcid(),
                "githubUrl", p.getGithubUrl(),
                "expertiseCategoryIds", p.getExpertises().stream().map(c -> c.getId().toString()).sorted().toList(),
                "customExpertise", List.copyOf(p.getCustomExpertise()));
    }

    private static List<String> namesOf(Set<Category> categories) {
        return categories.stream().map(Category::getName).toList();
    }

    private static UUID idOf(MediaFile m) {
        return m == null ? null : m.getId();
    }

    /** Touch lazy associations the mapper reads (user display name + expertise ids) before the session closes. */
    private static void initForMapping(TutorProfile p) {
        if (p.getUser() != null) p.getUser().getFirstName();   // init lazy user proxy
        p.getExpertises().size();                              // init lazy ManyToMany
    }

    /**
     * Approves or rejects an expert. The allowed moves are PENDING to either decision, REJECTED
     * back to APPROVED (the dashboard's re-approve) and APPROVED to REJECTED, which withdraws an
     * approval. Deciding the same thing twice is refused with 409 INVALID_TUTOR_TRANSITION.
     *
     * <p>Withdrawing takes the TUTOR role back and ends the expert's sessions. Rejecting used to
     * change the profile's status and nothing else, so a withdrawn expert kept course:update_own
     * and went on editing and submitting their courses; only creating one was refused. What
     * happens to their published courses stays an administrator's decision: they remain as they
     * are until reassigned or unpublished.
     */
    @Transactional
    public TutorProfile decide(UUID tutorId, UUID adminId, ApprovalDecisionRequest req) {
        // Only an expert who still has an account: granting or taking a role needs one.
        TutorProfile tutor = profiles.findWithLiveAccountById(tutorId)
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND", "Tutor does not exist"));

        boolean approved = req.decision() == BookingDecision.APPROVED;
        TutorApprovalStatus from = tutor.getApprovalStatus();
        requireTransition(from, approved);
        boolean revoking = !approved && from == TutorApprovalStatus.APPROVED;
        Instant now = Instant.now();

        tutor.setApprovalStatus(approved ? TutorApprovalStatus.APPROVED : TutorApprovalStatus.REJECTED);
        tutor.setApprovedAt(approved ? now : null);
        tutor.setApprovedBy(approved ? adminId : null);
        tutor.setRejectionReason(approved ? null : req.note());
        profiles.save(tutor);

        TutorApprovalRequest pending = approvals.findAllByTutorIdOrderBySubmittedAtDesc(tutorId).stream()
                .filter(a -> a.getStatus() == ApprovalStatus.PENDING)
                .findFirst()
                .orElseGet(() -> {
                    TutorApprovalRequest r = TutorApprovalRequest.builder()
                            .tutor(tutor)
                            .status(ApprovalStatus.PENDING)
                            .submittedAt(now)
                            .build();
                    r.setId(UUID.randomUUID());
                    return approvals.save(r);
                });
        pending.setStatus(approved ? ApprovalStatus.APPROVED : ApprovalStatus.REJECTED);
        pending.setDecisionNote(req.note());
        pending.setDecidedAt(now);
        pending.setDecidedBy(users.getReferenceById(adminId));
        approvals.save(pending);

        User user = tutor.getUser();
        if (approved) {
            grantTutorRole(user);
        } else if (revoking) {
            revokeTutorRole(user);
        }
        audit.record(approved ? AuditService.Actions.APPROVE : AuditService.Actions.REJECT,
                "TUTOR_PROFILE", tutor.getId(),
                AuditService.snapshot("status", from.name()),
                AuditService.snapshot("status", tutor.getApprovalStatus().name(), "note", req.note(),
                        "tutorRoleRevoked", revoking));
        events.publishEvent(new DecisionEvents.TutorDecided(user.getId(), approved, revoking, req.note()));
        initForMapping(tutor);
        return tutor;
    }

    private static void requireTransition(TutorApprovalStatus from, boolean approve) {
        boolean allowed = switch (from) {
            case PENDING, SUSPENDED -> true;
            case REJECTED -> approve;
            case APPROVED -> !approve;
        };
        if (!allowed) {
            throw Errors.conflict("INVALID_TUTOR_TRANSITION",
                    "This expert is already " + from + "; the decision would change nothing");
        }
    }

    /**
     * Takes TUTOR away and signs the account out everywhere, so the permissions the role carried
     * end now rather than with the last access token or at the next refresh.
     */
    private void revokeTutorRole(User user) {
        boolean removed = user.getUserRoles().removeIf(ur -> ur.getRole().getCode() == RoleCode.TUTOR);
        if (removed) {
            sessions.revokeAccessTokens(user);
            users.save(user);
        }
        refreshTokens.revokeAllForUser(user.getId(), TokenRevokeReason.ADMIN);
    }

    private void grantTutorRole(User user) {
        Role tutorRole = roles.findByCode(RoleCode.TUTOR)
                .orElseThrow(() -> new IllegalStateException("Role TUTOR missing from seed data"));
        boolean already = user.getUserRoles().stream()
                .anyMatch(ur -> ur.getRole().getCode() == RoleCode.TUTOR);
        if (already) return;

        UserRole link = UserRole.builder()
                .id(new UserRoleId(user.getId(), tutorRole.getId()))
                .user(user)
                .role(tutorRole)
                .grantedAt(Instant.now())
                .build();
        user.getUserRoles().add(link);
        // The applicant's current access token predates the role; making it stale sends the
        // dashboard through a refresh, which picks up the expert permissions straight away.
        sessions.revokeAccessTokens(user);
        users.save(user);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** A plausible career length, or nothing: a stored 500 years blocked every later profile save. */
    private static Short plausibleYears(Short years) {
        return years == null || years < 0 || years > 80 ? null : years;
    }

    /** An http(s) address with a host and no credentials, trimmed; anything else is dropped. */
    private static String webAddressOrNull(String raw) {
        String value = trimToNull(raw);
        return value != null && HttpUrlValidator.isWebAddress(value) ? value : null;
    }
}
