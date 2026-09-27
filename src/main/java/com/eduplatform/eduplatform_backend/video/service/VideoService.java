package com.eduplatform.eduplatform_backend.video.service;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.enums.MediaVisibility;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.course.repo.CourseRepository;
import com.eduplatform.eduplatform_backend.course.repo.LessonRepository;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.media.storage.StorageService;
import com.eduplatform.eduplatform_backend.media.upload.AllowedMediaType;
import com.eduplatform.eduplatform_backend.media.upload.UploadKind;
import com.eduplatform.eduplatform_backend.media.upload.UploadPolicy;
import com.eduplatform.eduplatform_backend.tutor.repo.TutorProfileRepository;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoAssetDto;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoCompleteRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tutor video library. Videos are stored as {@link MediaFile} rows (LOCAL storage) and
 * uploaded with an init → PUT bytes → complete handshake matching the dashboard's flow.
 */
@Service
public class VideoService {

    /** Filenames are display metadata only; a longer one is junk, not a name. */
    private static final int MAX_FILENAME_LENGTH = 255;

    private final MediaFileRepository media;
    private final StorageService storage;
    private final UploadPolicy policy;
    private final LessonRepository lessons;
    private final CourseRepository courses;
    private final TutorProfileRepository tutors;
    private final TransactionTemplate tx;

    public VideoService(MediaFileRepository media, StorageService storage, UploadPolicy policy,
                        LessonRepository lessons, CourseRepository courses, TutorProfileRepository tutors,
                        PlatformTransactionManager transactionManager) {
        this.media = media;
        this.storage = storage;
        this.policy = policy;
        this.lessons = lessons;
        this.courses = courses;
        this.tutors = tutors;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public VideoInitResponse init(UUID ownerId, VideoInitRequest req) {
        // Both checks fail the client before it spends minutes uploading: the browser already
        // knows the file's size and type, so an oversized or non-video file is refused here
        // rather than after half a gigabyte has crossed the wire.
        AllowedMediaType declared = policy.declaredVideoType(req.mime());
        policy.requireWithinLimit(UploadKind.VIDEO, req.sizeBytes());

        String filename = cleanDisplayName(req.filename());
        Map<String, Object> meta = new HashMap<>();
        meta.put("kind", "video");
        meta.put("filename", filename);
        meta.put("title", stripExtension(filename));

        UUID id = UUID.randomUUID();
        MediaFile m = MediaFile.builder()
                .ownerUserId(ownerId)
                .storage(storage.type())
                .objectKey("pending/" + id)            // replaced once bytes arrive
                .mimeType(declared.mime())
                .byteSize(Math.max(0, req.sizeBytes()))
                .status(MediaStatus.PENDING)
                .visibility(MediaVisibility.PRIVATE)
                .metadata(meta)
                .build();
        m.setId(id);
        media.save(m);
        return new VideoInitResponse("/api/videos/" + id + "/content", id);
    }

    /**
     * Receives the video's bytes. Deliberately not one transaction: the body streams at the
     * uploader's speed (nginx forwards it unbuffered), so a transaction around it held one of the
     * pool's connections for the minutes a large upload takes, and a handful of uploads starved
     * every other request into 500s once the pool's 10-second wait ran out. Ownership is checked
     * first in a short read, the bytes are stored with no connection held, and the row is updated
     * in a short transaction of its own that re-reads it. If that update fails the stored object
     * is removed again, and a previous object is removed only once the new one is recorded.
     */
    public void storeBytes(UUID ownerId, UUID videoId, InputStream body, String contentType, long declaredLength) {
        MediaFile m = requireOwned(ownerId, videoId);
        policy.requireWithinLimit(UploadKind.VIDEO, declaredLength);

        AllowedMediaType type;
        StorageService.Stored stored;
        // The PUT may re-declare the type; fall back to what /init recorded. Either way the
        // leading bytes have to prove it really is one of the allowed video containers, and the
        // cap is enforced as they stream — a lying Content-Length buys nothing.
        String declared = (contentType == null || contentType.isBlank()) ? m.getMimeType() : contentType;
        try {
            byte[] head = body.readNBytes(UploadPolicy.SNIFF_BYTES);
            type = policy.validateVideo(declared, head, declaredLength);
            stored = storage.store(new SequenceInputStream(new ByteArrayInputStream(head), body),
                    type.extension(), policy.maxBytes(UploadKind.VIDEO));
        } catch (IOException e) {
            throw Errors.unprocessable("UPLOAD_READ_FAILED", "Could not read the uploaded video stream");
        }

        String previousKey;
        try {
            previousKey = tx.execute(status -> {
                MediaFile current = requireOwned(ownerId, videoId);
                String before = current.getObjectKey();
                current.setObjectKey(stored.objectKey());
                current.setByteSize(stored.size());
                current.setChecksumSha256(stored.sha256());
                current.setMimeType(type.mime());
                current.setStatus(MediaStatus.PROCESSING);
                media.save(current);
                return before;
            });
        } catch (RuntimeException recordFailed) {
            storage.delete(stored.objectKey());
            throw recordFailed;
        }

        // Re-PUTting bytes for the same video would otherwise strand the first object on disk
        // with nothing referencing it.
        if (previousKey != null && !previousKey.startsWith("pending/") && !previousKey.equals(stored.objectKey())) {
            storage.delete(previousKey);
        }
    }

    @Transactional
    public VideoAssetDto complete(UUID ownerId, UUID videoId, VideoCompleteRequest req) {
        MediaFile m = requireOwned(ownerId, videoId);
        if (m.getStatus() == MediaStatus.PENDING) {
            throw Errors.conflict("VIDEO_NOT_UPLOADED", "Upload the video bytes before completing");
        }
        if (req != null && req.title() != null && !req.title().isBlank()) {
            ensureMetadata(m).put("title", cleanDisplayName(req.title()));
        }
        m.setStatus(MediaStatus.READY);
        media.save(m);
        return toDto(m);
    }

    @Transactional(readOnly = true)
    public Page<VideoAssetDto> list(UUID ownerId, Pageable pageable) {
        return media.findVideosByOwner(ownerId, pageable).map(VideoService::toDto);
    }

    /**
     * Deletes one of the caller's videos and detaches it from everything that uses it: the lessons
     * it is the file of and the courses it is the trailer of go on without it. The row is only
     * soft-deleted, so the foreign keys' ON DELETE SET NULL never fired, and every reference kept
     * pointing at a file that now answered 404 — the course page went on listing it.
     *
     * <p>The bytes are removed once the transaction has committed, so a rollback cannot leave a
     * row pointing at a file that is gone.
     */
    @Transactional
    public void delete(UUID ownerId, UUID videoId) {
        MediaFile m = requireOwned(ownerId, videoId);
        String key = m.getObjectKey();
        lessons.detachMedia(videoId);
        courses.detachThumbnail(videoId);
        courses.detachTrailer(videoId);
        tutors.detachAvatar(videoId);
        media.delete(m); // soft-delete via @SQLDelete
        if (key != null && !key.startsWith("pending/")) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storage.delete(key);
                }
            });
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    /**
     * One of the caller's videos. Anything that is not a video is "not found" here, whatever its
     * owner: this endpoint used to delete (or overwrite) any file the caller had uploaded — a
     * lesson's PDF, a portrait, a course cover — through the video library.
     */
    private MediaFile requireOwned(UUID ownerId, UUID videoId) {
        MediaFile m = media.findById(videoId)
                .filter(f -> f.getMimeType() != null && f.getMimeType().startsWith("video/"))
                .orElseThrow(() -> Errors.notFound("VIDEO_NOT_FOUND", "Video does not exist"));
        if (m.getOwnerUserId() == null || !m.getOwnerUserId().equals(ownerId)) {
            throw Errors.forbidden("NOT_VIDEO_OWNER", "This video does not belong to you");
        }
        return m;
    }

    private static Map<String, Object> ensureMetadata(MediaFile m) {
        if (m.getMetadata() == null) {
            m.setMetadata(new HashMap<>());
        } else if (!(m.getMetadata() instanceof HashMap)) {
            m.setMetadata(new HashMap<>(m.getMetadata()));
        }
        return m.getMetadata();
    }

    private static VideoAssetDto toDto(MediaFile m) {
        Map<String, Object> meta = m.getMetadata() == null ? Map.of() : m.getMetadata();
        String filename = meta.get("filename") == null ? null : String.valueOf(meta.get("filename"));
        String title = meta.get("title") == null ? filename : String.valueOf(meta.get("title"));
        return new VideoAssetDto(
                m.getId(),
                title,
                filename,
                "/api/media/" + m.getId() + "/content",
                null,
                m.getDurationSec() == null ? 0 : m.getDurationSec(),
                m.getByteSize(),
                statusOf(m.getStatus()),
                null,
                null,
                m.getCreatedAt());
    }

    private static String statusOf(MediaStatus s) {
        return switch (s) {
            case PENDING -> "UPLOADING";
            case UPLOADED, PROCESSING -> "PROCESSING";
            case READY -> "READY";
            case FAILED -> "FAILED";
        };
    }

    /**
     * Keep the tutor's own wording (including non-Latin titles) but drop control characters and
     * any path the browser may have prefixed: this value is echoed back to the dashboard and is
     * the basis of the download filename, which is a header.
     */
    private static String cleanDisplayName(String text) {
        if (text == null || text.isBlank()) {
            return "Untitled video";
        }
        String base = text.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        String cleaned = base.replaceAll("\\p{Cntrl}", "").trim();
        if (cleaned.isBlank()) {
            return "Untitled video";
        }
        return cleaned.length() > MAX_FILENAME_LENGTH ? cleaned.substring(0, MAX_FILENAME_LENGTH) : cleaned;
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "Untitled video";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
