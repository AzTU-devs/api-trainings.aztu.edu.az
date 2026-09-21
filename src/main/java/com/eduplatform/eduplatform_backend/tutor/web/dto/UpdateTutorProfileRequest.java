package com.eduplatform.eduplatform_backend.tutor.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Getter;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * The one body for both profile edits: the expert's own ({@code PATCH /api/portal/tutor/me}) and
 * an admin's ({@code PATCH /api/admin/tutors/{tutorId}}).
 *
 * <p>Merge-patch semantics: a property left out of the body is left unchanged, and a property sent
 * as {@code null} — or, for text, as a blank string, which is what an emptied form input sends — is
 * cleared. That difference is why this is a class with setters rather than a record: Jackson hands
 * a record's constructor the same null for "absent" and for "null", and without telling them apart
 * an expert could never remove a GitHub link or a portrait once set. Each setter records that its
 * property was present, which {@link #has} reports.
 *
 * <p>{@code expertiseCategoryIds} is the exception. An expert is always filed under at least one
 * area, as the application requires, so a list replaces the areas and null in any form leaves
 * them as they are.
 *
 * <p>There is deliberately no approval status here: approving or rejecting stays with the
 * decision endpoint, and unknown properties are ignored.
 */
@Getter
public class UpdateTutorProfileRequest {

    /** The bare ORCID iD; surrounding blanks and a lower-case check digit are tidied on save. */
    private static final String ORCID_OR_BLANK = "^\\s*(\\d{4}-\\d{4}-\\d{4}-\\d{3}[\\dXx])?\\s*$";

    /** The properties that can be cleared, and so whose presence in the body matters. */
    public enum Field {
        HEADLINE, BIO, YEARS_EXPERIENCE, WEBSITE_URL, LINKEDIN_URL, AVATAR_MEDIA_ID,
        ACADEMIC_TITLE, DEPARTMENT, EDUCATION, CERTIFICATIONS, LANGUAGES,
        GOOGLE_SCHOLAR_URL, RESEARCH_GATE_URL, ORCID, GITHUB_URL
    }

    // No getter: a getter on a collection doubles as a setter in Jackson, which would let a
    // client claim properties it never sent.
    @Getter(AccessLevel.NONE)
    private final Set<Field> present = EnumSet.noneOf(Field.class);

    @Size(max = 160)
    private String headline;

    @Size(max = 5000)
    private String bio;

    @Min(0) @Max(80)
    private Short yearsExperience;

    @Size(max = 255) @HttpUrl
    private String websiteUrl;

    @Size(max = 255) @HttpUrl
    private String linkedinUrl;

    /** An uploaded image; see the tutor service for whose uploads qualify. */
    private UUID avatarMediaId;

    @Size(max = 120)
    private String academicTitle;

    @Size(max = 160)
    private String department;

    /** One qualification per line. */
    @Size(max = 5000)
    private String education;

    /** One certification per line. */
    @Size(max = 5000)
    private String certifications;

    @Size(max = 255)
    private String languages;

    @Size(max = 255) @HttpUrl
    private String googleScholarUrl;

    @Size(max = 255) @HttpUrl
    private String researchGateUrl;

    @Pattern(regexp = ORCID_OR_BLANK, message = "must be an ORCID iD of the form 0000-0000-0000-000X")
    private String orcid;

    @Size(max = 255) @HttpUrl
    private String githubUrl;

    @Size(min = 1, message = "must name at least one area of expertise")
    private Set<UUID> expertiseCategoryIds;

    /** Whether the body contained this property at all, explicit null included. */
    public boolean has(Field field) {
        return present.contains(field);
    }

    public void setHeadline(String headline)                 { this.headline = headline;                 present.add(Field.HEADLINE); }
    public void setBio(String bio)                           { this.bio = bio;                           present.add(Field.BIO); }
    public void setYearsExperience(Short yearsExperience)    { this.yearsExperience = yearsExperience;   present.add(Field.YEARS_EXPERIENCE); }
    public void setWebsiteUrl(String websiteUrl)             { this.websiteUrl = websiteUrl;             present.add(Field.WEBSITE_URL); }
    public void setLinkedinUrl(String linkedinUrl)           { this.linkedinUrl = linkedinUrl;           present.add(Field.LINKEDIN_URL); }
    public void setAvatarMediaId(UUID avatarMediaId)         { this.avatarMediaId = avatarMediaId;       present.add(Field.AVATAR_MEDIA_ID); }
    public void setAcademicTitle(String academicTitle)       { this.academicTitle = academicTitle;       present.add(Field.ACADEMIC_TITLE); }
    public void setDepartment(String department)             { this.department = department;             present.add(Field.DEPARTMENT); }
    public void setEducation(String education)               { this.education = education;               present.add(Field.EDUCATION); }
    public void setCertifications(String certifications)     { this.certifications = certifications;     present.add(Field.CERTIFICATIONS); }
    public void setLanguages(String languages)               { this.languages = languages;               present.add(Field.LANGUAGES); }
    public void setGoogleScholarUrl(String googleScholarUrl) { this.googleScholarUrl = googleScholarUrl; present.add(Field.GOOGLE_SCHOLAR_URL); }
    public void setResearchGateUrl(String researchGateUrl)   { this.researchGateUrl = researchGateUrl;   present.add(Field.RESEARCH_GATE_URL); }
    public void setOrcid(String orcid)                       { this.orcid = orcid;                       present.add(Field.ORCID); }
    public void setGithubUrl(String githubUrl)               { this.githubUrl = githubUrl;               present.add(Field.GITHUB_URL); }

    public void setExpertiseCategoryIds(Set<UUID> expertiseCategoryIds) {
        this.expertiseCategoryIds = expertiseCategoryIds;
    }
}
