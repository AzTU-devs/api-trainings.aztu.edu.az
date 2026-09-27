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
 * <p>The file must have been uploaded by whoever is setting it. An avatar is served anonymously
 * as soon as the expert is approved, so naming someone else's upload would publish it — and that
 * includes the expert's own uploads when an admin is the one editing: an expert's files are
 * mostly lesson material and documents they chose to keep private, and an admin picking one by
 * id could put, say, a scanned diploma on the public page. On the expert's own edit the caller is
 * the expert; on an admin's edit the admin uploads the portrait themselves, which is what the
 * dashboard's Edit dialog does anyway.
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
        if (owner == null || !owner.equals(caller.userId())) {
            throw Errors.forbidden("MEDIA_FORBIDDEN",
                    PROPERTY + " must refer to an image you uploaded yourself");
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
