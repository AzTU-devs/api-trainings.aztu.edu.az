package com.eduplatform.eduplatform_backend.tutor.service;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.media.upload.AllowedMediaType;
import com.eduplatform.eduplatform_backend.media.upload.UploadKind;
import com.eduplatform.eduplatform_backend.tutor.domain.TutorProfile;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * The rule for pointing a profile's avatar at an uploaded file. It answers with the same codes, in
 * the same order, as the course media check (CourseMediaValidator): 404 MEDIA_NOT_FOUND, then
 * 403 MEDIA_FORBIDDEN, then 422 INVALID_MEDIA_FOR_FIELD.
 *
 * <p>Ownership is where it differs. Course fields exempt admins outright; an avatar does not,
 * because it is served anonymously as soon as the expert is approved, and an admin free to name
 * any id could publish a private upload of anyone at all. So the file must have been uploaded by
 * the expert whose profile it is or by whoever is editing it — on the expert's own edit those are
 * the same person, and on an admin's edit that admin.
 *
 * <p>The kind comes from the stored MIME type through the upload allowlist, the table the upload
 * endpoint validated against, so the image types accepted here can never drift from the ones an
 * upload can produce.
 */
@Component
public class TutorAvatarValidator {

    private static final String PROPERTY = "avatarMediaId";

    private final MediaFileRepository media;

    public TutorAvatarValidator(MediaFileRepository media) {
        this.media = media;
    }

    /** Loads the image to become {@code profile}'s avatar, or throws as the class comment describes. */
    public MediaFile resolve(UUID mediaId, TutorProfile profile, AuthenticatedPrincipal caller) {
        MediaFile m = media.findById(mediaId)
                .orElseThrow(() -> Errors.notFound("MEDIA_NOT_FOUND", "Media " + mediaId + " does not exist"));

        UUID owner = m.getOwnerUserId();
        // The profile's user is a lazy proxy; reading its id does not load it.
        boolean mayUse = owner != null
                && (owner.equals(profile.getUser().getId()) || owner.equals(caller.userId()));
        if (!mayUse) {
            throw Errors.forbidden("MEDIA_FORBIDDEN",
                    PROPERTY + " must refer to an image uploaded by the expert or by you");
        }

        if (m.getStatus() != MediaStatus.READY) {
            throw Errors.unprocessable("INVALID_MEDIA_FOR_FIELD",
                    PROPERTY + " refers to media that has not finished uploading");
        }
        UploadKind kind = AllowedMediaType.fromMime(m.getMimeType()).map(AllowedMediaType::kind).orElse(null);
        if (kind != UploadKind.IMAGE) {
            throw Errors.unprocessable("INVALID_MEDIA_FOR_FIELD",
                    PROPERTY + " must be an image, but media " + m.getId() + " is " + m.getMimeType());
        }
        return m;
    }
}
