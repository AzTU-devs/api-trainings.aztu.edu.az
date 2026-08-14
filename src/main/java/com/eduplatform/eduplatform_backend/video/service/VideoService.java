package com.eduplatform.eduplatform_backend.video.service;

import com.eduplatform.eduplatform_backend.common.enums.MediaStatus;
import com.eduplatform.eduplatform_backend.common.enums.MediaVisibility;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.media.domain.MediaFile;
import com.eduplatform.eduplatform_backend.media.repo.MediaFileRepository;
import com.eduplatform.eduplatform_backend.media.storage.StorageService;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoAssetDto;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoCompleteRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tutor video library. Videos are stored as {@link MediaFile} rows (LOCAL storage) and
 * uploaded with an init → PUT bytes → complete handshake matching the dashboard's flow.
 */
@Service
public class VideoService {

    private final MediaFileRepository media;
    private final StorageService storage;

    public VideoService(MediaFileRepository media, StorageService storage) {
        this.media = media;
        this.storage = storage;
    }

    @Transactional
    public VideoInitResponse init(UUID ownerId, VideoInitRequest req) {
        Map<String, Object> meta = new HashMap<>();
        meta.put("kind", "video");
        meta.put("filename", req.filename());
        meta.put("title", stripExtension(req.filename()));

        UUID id = UUID.randomUUID();
        MediaFile m = MediaFile.builder()
                .ownerUserId(ownerId)
                .storage(storage.type())
                .objectKey("pending/" + id)            // replaced once bytes arrive
                .mimeType(normalizeMime(req.mime()))
                .byteSize(Math.max(0, req.sizeBytes()))
                .status(MediaStatus.PENDING)
                .visibility(MediaVisibility.PRIVATE)
                .metadata(meta)
                .build();
        m.setId(id);
        media.save(m);
        return new VideoInitResponse("/api/videos/" + id + "/content", id);
    }

    @Transactional
    public void storeBytes(UUID ownerId, UUID videoId, InputStream body, String contentType) {
        MediaFile m = requireOwned(ownerId, videoId);
        String filename = m.getMetadata() == null ? null : String.valueOf(m.getMetadata().get("filename"));
        StorageService.Stored stored = storage.store(body, filename);
        m.setObjectKey(stored.objectKey());
        m.setByteSize(stored.size());
        m.setChecksumSha256(stored.sha256());
        if (contentType != null && contentType.startsWith("video/")) {
            m.setMimeType(contentType);
        }
        m.setStatus(MediaStatus.PROCESSING);
        media.save(m);
    }

    @Transactional
    public VideoAssetDto complete(UUID ownerId, UUID videoId, VideoCompleteRequest req) {
        MediaFile m = requireOwned(ownerId, videoId);
        if (m.getStatus() == MediaStatus.PENDING) {
            throw Errors.conflict("VIDEO_NOT_UPLOADED", "Upload the video bytes before completing");
        }
        if (req != null && req.title() != null && !req.title().isBlank()) {
            ensureMetadata(m).put("title", req.title().trim());
        }
        m.setStatus(MediaStatus.READY);
        media.save(m);
        return toDto(m);
    }

    @Transactional(readOnly = true)
    public Page<VideoAssetDto> list(UUID ownerId, Pageable pageable) {
        return media.findVideosByOwner(ownerId, pageable).map(VideoService::toDto);
    }

    @Transactional
    public void delete(UUID ownerId, UUID videoId) {
        MediaFile m = requireOwned(ownerId, videoId);
        String key = m.getObjectKey();
        media.delete(m); // soft-delete via @SQLDelete
        if (key != null && !key.startsWith("pending/")) {
            storage.delete(key);
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private MediaFile requireOwned(UUID ownerId, UUID videoId) {
        MediaFile m = media.findById(videoId)
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

    private static String normalizeMime(String mime) {
        return (mime == null || mime.isBlank()) ? "video/mp4" : mime;
    }

    private static String stripExtension(String filename) {
        if (filename == null) return "Untitled video";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }
}
