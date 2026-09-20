package com.eduplatform.eduplatform_backend.course.service;

import com.eduplatform.eduplatform_backend.common.enums.LessonContentType;
import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.enums.RoleCode;
import com.eduplatform.eduplatform_backend.common.error.AppException;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.media.upload.AllowedMediaType;
import com.eduplatform.eduplatform_backend.media.upload.UploadKind;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The one rule for pointing a course or lesson field at an uploaded file. Course fields and
 * lesson material both go through it, so the checks cannot drift apart between the two.
 *
 * <p>Attaching a file publishes it further than its owner chose: a published course's thumbnail
 * and trailer are served anonymously, and a lesson's file is streamed to every student enrolled
 * in the course. Without the ownership check a tutor could expose another user's private upload
 * just by naming its id. Admins are exempt — they assemble courses from whatever the university
 * has uploaded.
 *
 * <p>The kind comes from the stored MIME type through the upload allowlist, the same table the
 * upload endpoints validate against, so a row that predates the allowlist cannot be attached
 * anywhere, a PDF cannot become a cover image and a PDF lesson cannot be handed a video.
 */
@Component
public class CourseMediaValidator {

    private final MediaFileRepository media;

    public CourseMediaValidator(MediaFileRepository media) {
        this.media = media;
    }

    /** A media-backed request field: its name in the API, and the upload kinds it accepts. */
    public record MediaField(String property, Set<UploadKind> kinds, String description) {

        private static final String LESSON_PROPERTY = "videoMediaId";

        public static final MediaField COURSE_THUMBNAIL =
                new MediaField("thumbnailMediaId", Set.of(UploadKind.IMAGE), "an image");
        public static final MediaField COURSE_TRAILER =
                new MediaField("trailerMediaId", Set.of(UploadKind.VIDEO), "a video");

        public MediaField {
            kinds = Set.copyOf(kinds);
        }

        /**
         * A lesson's single file. Despite its name, {@code videoMediaId} holds whatever the lesson
         * delivers: the video of a VIDEO lesson, the document of a PDF lesson. TEXT, QUIZ and
         * LIVE_SESSION lessons carry it as an optional attachment, and the dashboard offers images,
         * PDFs and videos there, so for them the allowlist itself is the only limit on kind.
         */
        public static MediaField lessonMaterial(LessonContentType contentType) {
            return switch (contentType) {
                case VIDEO -> new MediaField(LESSON_PROPERTY, Set.of(UploadKind.VIDEO),
                        "a video for a VIDEO lesson");
                case PDF -> new MediaField(LESSON_PROPERTY, Set.of(UploadKind.DOCUMENT),
                        "a PDF document for a PDF lesson");
                case TEXT, QUIZ, LIVE_SESSION -> new MediaField(LESSON_PROPERTY, EnumSet.allOf(UploadKind.class),
                        "an image, a video or a PDF document");
            };
        }
    }

    /**
     * Loads the media a field is being pointed at, or null for no id: 404 MEDIA_NOT_FOUND if it
     * does not exist, 403 MEDIA_FORBIDDEN if the caller neither owns it nor is an admin, and
     * 422 INVALID_MEDIA_FOR_FIELD unless it has finished uploading and is of a kind the field
     * accepts.
     */
    public MediaFile resolve(UUID mediaId, MediaField field, AuthenticatedPrincipal caller) {
        if (mediaId == null) return null;
        MediaFile m = media.findById(mediaId).orElseThrow(() -> notFound(mediaId));
        if (!isAdmin(caller) && !Objects.equals(caller.userId(), m.getOwnerUserId())) {
            throw Errors.forbidden("MEDIA_FORBIDDEN",
                    field.property() + " must refer to media you uploaded yourself");
        }
        requireFits(m, field);
        return m;
    }

    /**
     * Re-checks a file that stays attached while the kind its field accepts changes, as when a
     * lesson switches content type. Same outcomes as {@link #resolve}, minus ownership: keeping
     * the file exposes it to no one new, and the tutor editing now need not be the one who
     * attached it, since an admin can hand a course to another tutor.
     *
     * @param kept the entity's current association, typically still a lazy proxy
     */
    public void recheckKept(MediaFile kept, MediaField field) {
        // Existence is asked with a query first. The file may have been soft-deleted since it
        // was attached (the video library deletes without detaching), and a lazy proxy whose
        // row @SQLRestriction now hides throws EntityNotFoundException when initialised: a 500.
        // findById goes through that same proxy; a query is filtered like any other read.
        if (!media.existsById(kept.getId())) {
            throw notFound(kept.getId());
        }
        requireFits(kept, field);
    }

    private static void requireFits(MediaFile m, MediaField field) {
        if (m.getStatus() != MediaStatus.READY) {
            throw Errors.unprocessable("INVALID_MEDIA_FOR_FIELD",
                    field.property() + " refers to media that has not finished uploading");
        }
        UploadKind kind = AllowedMediaType.fromMime(m.getMimeType()).map(AllowedMediaType::kind).orElse(null);
        // Null first: the immutable set behind kinds() throws on contains(null).
        if (kind == null || !field.kinds().contains(kind)) {
            throw Errors.unprocessable("INVALID_MEDIA_FOR_FIELD",
                    field.property() + " must be " + field.description() + ", but media " + m.getId()
                            + " is " + m.getMimeType());
        }
    }

    private static AppException notFound(UUID mediaId) {
        return Errors.notFound("MEDIA_NOT_FOUND", "Media " + mediaId + " does not exist");
    }

    private static boolean isAdmin(AuthenticatedPrincipal caller) {
        return caller.roles().contains(RoleCode.ADMIN.name())
                || caller.roles().contains(RoleCode.SUPER_ADMIN.name());
    }
}
