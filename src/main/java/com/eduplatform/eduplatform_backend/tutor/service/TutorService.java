package com.eduplatform.eduplatform_backend.tutor.service;

import com.eduplatform.eduplatform_backend.catalog.domain.Category;
import com.eduplatform.eduplatform_backend.catalog.repo.CategoryRepository;
import com.eduplatform.eduplatform_backend.common.enums.ApprovalStatus;
import com.eduplatform.eduplatform_backend.common.enums.BookingDecision;
import com.eduplatform.eduplatform_backend.common.enums.EnrollmentStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
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
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorApprovalRequest;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorApprovalRequestRepository;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import com.eduplatform.eduplatform_backend.tutor.web.dto.ApprovalDecisionRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorApplyRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.TutorStudentDto;
import com.eduplatform.eduplatform_backend.tutor.web.dto.UpdateTutorProfileRequest;
import com.eduplatform.eduplatform_backend.tutor.web.dto.UpdateTutorProfileRequest.Field;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
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

    public TutorService(TutorProfileRepository profiles, TutorApprovalRequestRepository approvals,
                        UserRepository users, RoleRepository roles, CategoryRepository categories,
                        EnrollmentRepository enrollments, AuditService audit, TutorAvatarValidator avatars) {
        this.profiles = profiles;
        this.approvals = approvals;
        this.users = users;
        this.roles = roles;
        this.categories = categories;
        this.enrollments = enrollments;
        this.audit = audit;
        this.avatars = avatars;
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

    @Transactional
    public TutorProfile apply(UUID userId, TutorApplyRequest req) {
        if (profiles.existsByUserId(userId)) {
            throw Errors.conflict("TUTOR_PROFILE_EXISTS", "You already have a tutor profile; submit a new approval request instead");
        }
        User user = users.findById(userId)
                .orElseThrow(() -> Errors.notFound("USER_NOT_FOUND", "User does not exist"));

        Set<Category> expertise = resolveCategories(req.categoryIds());

        TutorProfile profile = TutorProfile.builder()
                .user(user)
                .headline(req.headline())
                .bio(req.bio())
                .yearsExperience(req.yearsExperience())
                .websiteUrl(req.websiteUrl())
                .linkedinUrl(req.linkedinUrl())
                .approvalStatus(TutorApprovalStatus.PENDING)
                .expertises(expertise)
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

    /** Public, read-only view of an APPROVED tutor profile. */
    @Transactional(readOnly = true)
    public TutorProfile publicProfile(UUID tutorId) {
        TutorProfile p = profiles.findById(tutorId)
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

    @Transactional(readOnly = true)
    public Page<TutorProfile> listByStatus(TutorApprovalStatus status, Pageable pageable) {
        Page<TutorProfile> page = profiles.findAllByApprovalStatus(status, pageable);
        page.forEach(TutorService::initForMapping);
        return page;
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

    /** An admin editing any expert's profile, whatever its approval status. */
    @Transactional
    public TutorProfile updateProfileByAdmin(AuthenticatedPrincipal caller, UUID tutorId,
                                             UpdateTutorProfileRequest req) {
        TutorProfile profile = profiles.findById(tutorId)
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
            p.setExpertises(resolveCategories(req.getExpertiseCategoryIds()));
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

    private Set<Category> resolveCategories(Set<UUID> ids) {
        Set<Category> resolved = new HashSet<>();
        for (UUID catId : ids) {
            // A null id would reach findById, which rejects it with a server error, not a 400.
            if (catId == null) {
                throw Errors.badRequest("INVALID_CATEGORY", "Unknown category: null");
            }
            resolved.add(categories.findById(catId)
                    .orElseThrow(() -> Errors.badRequest("INVALID_CATEGORY", "Unknown category: " + catId)));
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
                "expertiseCategoryIds", p.getExpertises().stream().map(c -> c.getId().toString()).sorted().toList());
    }

    private static UUID idOf(MediaFile m) {
        return m == null ? null : m.getId();
    }

    /** Touch lazy associations the mapper reads (user display name + expertise ids) before the session closes. */
    private static void initForMapping(TutorProfile p) {
        if (p.getUser() != null) p.getUser().getFirstName();   // init lazy user proxy
        p.getExpertises().size();                              // init lazy ManyToMany
    }

    @Transactional
    public TutorProfile decide(UUID tutorId, UUID adminId, ApprovalDecisionRequest req) {
        TutorProfile tutor = profiles.findById(tutorId)
                .orElseThrow(() -> Errors.notFound("TUTOR_PROFILE_NOT_FOUND", "Tutor does not exist"));

        boolean approved = req.decision() == BookingDecision.APPROVED;
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
        // We don't fetch the User entity here to keep the SQL light; the user reference is preserved by FK.
        approvals.save(pending);

        if (approved) {
            grantTutorRole(tutor.getUser());
        }
        audit.record(approved ? AuditService.Actions.APPROVE : AuditService.Actions.REJECT,
                "TUTOR_PROFILE", tutor.getId(), null,
                AuditService.snapshot("status", tutor.getApprovalStatus().name(), "note", req.note()));
        initForMapping(tutor);
        return tutor;
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
        users.save(user);
    }
}
