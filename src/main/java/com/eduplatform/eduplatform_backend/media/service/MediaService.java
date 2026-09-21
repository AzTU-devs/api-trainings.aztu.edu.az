package com.eduplatform.eduplatform_backend.media.service;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.enums.MediaVisibility;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.repo.LessonRepository;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.media.storage.StorageService;
import com.eduplatform.eduplatform_backend.media.upload.AllowedMediaType;
import com.eduplatform.eduplatform_backend.media.upload.UploadPolicy;
import com.eduplatform.eduplatform_backend.media.web.dto.MediaFileDto;
import com.eduplatform.eduplatform_backend.media.web.mapper.MediaMapper;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Upload + retrieval of binary media (lesson videos, PDFs, etc.). */
@Service
public class MediaService {

    /** Longest filename kept for the download header; anything longer is a nuisance, not a name. */
    private static final int MAX_FILENAME_LENGTH = 80;

    private final MediaFileRepository media;
    private final StorageService storage;
    private final MediaMapper mapper;
    private final LessonRepository lessons;
    private final CourseRepository courses;
    private final TutorProfileRepository tutors;
    private final UploadPolicy policy;

    public MediaService(MediaFileRepository media, StorageService storage, MediaMapper mapper,
                        LessonRepository lessons, CourseRepository courses, TutorProfileRepository tutors,
                        UploadPolicy policy) {
        this.media = media;
        this.storage = storage;
        this.mapper = mapper;
        this.lessons = lessons;
        this.courses = courses;
        this.tutors = tutors;
        this.policy = policy;
    }

    @Transactional
    public MediaFileDto upload(MultipartFile file, UUID ownerId) {
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("EMPTY_FILE", "Uploaded file is empty");
        }

        AllowedMediaType type;
        StorageService.Stored stored;
        // One pass over the body: sniff the head to decide what this really is, then hand that
        // same head back to storage in front of the remainder, so nothing is read twice or
        // buffered in memory.
        try (InputStream body = file.getInputStream()) {
            byte[] head = body.readNBytes(UploadPolicy.SNIFF_BYTES);
            type = policy.validate(file.getContentType(), head, file.getSize());
            stored = storage.store(new SequenceInputStream(new ByteArrayInputStream(head), body),
                    type.extension(), policy.maxBytes(type.kind()));
        } catch (IOException e) {
            throw Errors.unprocessable("UPLOAD_READ_FAILED", "Could not read the uploaded file");
        }

        MediaFile m = MediaFile.builder()
                .ownerUserId(ownerId)
                .storage(storage.type())
                .objectKey(stored.objectKey())
                // The validated type, never what the client declared: this value is what the
                // content endpoint later hands to the browser as Content-Type.
                .mimeType(type.mime())
                .byteSize(stored.size())
                .checksumSha256(stored.sha256())
                .status(MediaStatus.READY)
                .visibility(MediaVisibility.PRIVATE)
                .metadata(metadataOf(type, file.getOriginalFilename()))
                .build();
        m.setId(UUID.randomUUID());
        // With a hand-assigned id, save() merges: the auditing listener stamps createdAt on the
        // managed copy it returns and leaves m untouched, so the response is built from the copy.
        m = media.save(m);
        return mapper.toDto(m);
    }

    @Transactional(readOnly = true)
    public Content loadContent(UUID id, AuthenticatedPrincipal caller) {
        MediaFile m = media.findById(id)
                .orElseThrow(() -> Errors.notFound("MEDIA_NOT_FOUND", "Media does not exist"));
        if (!canAccess(m, caller)) {
            throw Errors.forbidden("MEDIA_FORBIDDEN", "You do not have access to this media");
        }
        return content(m);
    }

    /**
     * Anonymous read of a publicly visible asset — course thumbnails and trailers, and the
     * portraits of approved experts, which the marketing site embeds in {@code <img>}/{@code <video>}
     * tags. A portrait is public only while its profile is APPROVED: an applicant who is pending,
     * rejected or suspended has not been put in front of the public, and neither has their photo.
     *
     * <p>Anything that is not public is a 404, never a 401 or 403: an auth challenge on a tag the
     * browser loads by itself is useless at best (and, over Basic-style challenges, a credential
     * prompt), and a 403 would confirm that a given media id exists.
     */
    @Transactional(readOnly = true)
    public Content loadPublicContent(UUID id) {
        MediaFile m = media.findById(id)
                .filter(f -> f.getVisibility() == MediaVisibility.PUBLIC
                        || courses.isPublishedCourseAsset(f.getId())
                        || tutors.isApprovedTutorAvatar(f.getId()))
                .orElseThrow(() -> Errors.notFound("MEDIA_NOT_FOUND", "Media does not exist"));
        return content(m);
    }

    /**
     * Access policy for streaming media bytes: public assets, the owner, staff, published-course
     * marketing assets (thumbnail/trailer), an approved expert's portrait or the viewer's own,
     * and enrolled students viewing a lesson video.
     */
    private boolean canAccess(MediaFile m, AuthenticatedPrincipal caller) {
        if (m.getVisibility() == MediaVisibility.PUBLIC) return true;
        if (caller == null) return false;
        if (caller.userId().equals(m.getOwnerUserId())) return true;
        if (caller.roles().contains("ADMIN") || caller.roles().contains("SUPER_ADMIN")) return true;
        if (courses.isPublishedCourseAsset(m.getId())) return true;
        if (lessons.isLessonMediaViewableBy(m.getId(), caller.userId())) return true;
        // Asked last, so that streaming a lesson video — many range requests each — never pays for
        // it. The dashboard previews a portrait through this endpoint, so an expert must see their
        // own even when an admin uploaded it and the profile is not approved yet.
        return tutors.isTutorAvatarVisibleTo(m.getId(), caller.userId());
    }

    /**
     * Everything needed to serve the bytes safely. The stored type alone decides the headers —
     * a row whose type predates the upload allowlist is served as an opaque download rather than
     * as whatever an old client happened to claim it was.
     */
    private Content content(MediaFile m) {
        AllowedMediaType type = AllowedMediaType.fromMime(m.getMimeType()).orElse(null);
        Resource resource = storage.load(m.getObjectKey());
        return new Content(
                resource,
                type == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : type.mime(),
                type != null && type.kind().inlineSafe(),
                downloadName(m, type),
                m.getChecksumSha256());
    }

    /** Stored alongside the row so a later download has a name; already safe to put in a header. */
    private static Map<String, Object> metadataOf(AllowedMediaType type, String originalFilename) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("kind", type.kind().name().toLowerCase(Locale.ROOT));
        String stem = sanitizeStem(originalFilename);
        if (stem != null) {
            meta.put("filename", stem + type.extension());
        }
        return meta;
    }

    /**
     * Name offered when the browser saves the file: the original one if anything usable survives
     * sanitizing, else the media id. The extension always comes from the validated type, so a
     * saved copy cannot claim to be something the file is not.
     */
    private static String downloadName(MediaFile m, AllowedMediaType type) {
        String extension = type == null ? ".bin" : type.extension();
        Object stored = m.getMetadata() == null ? null : m.getMetadata().get("filename");
        String stem = stored == null ? null : sanitizeStem(String.valueOf(stored));
        return (stem == null ? "media-" + m.getId() : stem) + extension;
    }

    /**
     * Reduce a client-supplied filename to a plain ASCII stem. This lands in a
     * {@code Content-Disposition} header, so anything that could break out of the quoted string —
     * quotes, semicolons, CR/LF — has to go, along with any directory the client prefixed and its
     * extension. A name left with nothing but separators (a fully non-Latin one, say) is dropped.
     */
    private static String sanitizeStem(String filename) {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String base = filename.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        StringBuilder safe = new StringBuilder(base.length());
        for (int i = 0; i < base.length() && safe.length() < MAX_FILENAME_LENGTH; i++) {
            char c = base.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            safe.append(keep ? c : '-');
        }
        String cleaned = safe.toString().replaceAll("-{2,}", "-").replaceAll("^[.\\-]+", "").replaceAll("[.\\-]+$", "");
        return cleaned.isBlank() ? null : cleaned;
    }

    /** A loaded media resource plus everything the response headers are built from. */
    public record Content(Resource resource, String mimeType, boolean inline, String filename, String checksumSha256) {}
}
