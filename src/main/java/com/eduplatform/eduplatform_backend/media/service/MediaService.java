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
import com.eduplatform.eduplatform_backend.media.web.dto.MediaFileDto;
import com.eduplatform.eduplatform_backend.media.web.mapper.MediaMapper;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Upload + retrieval of binary media (lesson videos, PDFs, etc.). */
@Service
public class MediaService {

    private final MediaFileRepository media;
    private final StorageService storage;
    private final MediaMapper mapper;
    private final LessonRepository lessons;
    private final CourseRepository courses;

    public MediaService(MediaFileRepository media, StorageService storage, MediaMapper mapper,
                        LessonRepository lessons, CourseRepository courses) {
        this.media = media;
        this.storage = storage;
        this.mapper = mapper;
        this.lessons = lessons;
        this.courses = courses;
    }

    @Transactional
    public MediaFileDto upload(MultipartFile file, UUID ownerId) {
        StorageService.Stored stored = storage.store(file);
        String mime = file.getContentType() == null || file.getContentType().isBlank()
                ? "application/octet-stream"
                : file.getContentType();

        MediaFile m = MediaFile.builder()
                .ownerUserId(ownerId)
                .storage(storage.type())
                .objectKey(stored.objectKey())
                .mimeType(mime)
                .byteSize(stored.size())
                .checksumSha256(stored.sha256())
                .status(MediaStatus.READY)
                .visibility(MediaVisibility.PRIVATE)
                .build();
        m.setId(UUID.randomUUID());
        media.save(m);
        return mapper.toDto(m);
    }

    @Transactional(readOnly = true)
    public Content loadContent(UUID id, AuthenticatedPrincipal caller) {
        MediaFile m = media.findById(id)
                .orElseThrow(() -> Errors.notFound("MEDIA_NOT_FOUND", "Media does not exist"));
        if (!canAccess(m, caller)) {
            throw Errors.forbidden("MEDIA_FORBIDDEN", "You do not have access to this media");
        }
        Resource resource = storage.load(m.getObjectKey());
        return new Content(resource, m.getMimeType(), m.getByteSize());
    }

    /**
     * Access policy for streaming media bytes: public assets, the owner, staff, published-course
     * marketing assets (thumbnail/trailer), and enrolled students viewing a lesson video.
     */
    private boolean canAccess(MediaFile m, AuthenticatedPrincipal caller) {
        if (m.getVisibility() == MediaVisibility.PUBLIC) return true;
        if (caller == null) return false;
        if (caller.userId().equals(m.getOwnerUserId())) return true;
        if (caller.roles().contains("ADMIN") || caller.roles().contains("SUPER_ADMIN")) return true;
        if (courses.isPublishedCourseAsset(m.getId())) return true;
        return lessons.isLessonMediaViewableBy(m.getId(), caller.userId());
    }

    /** A loaded media resource plus the headers needed to serve it. */
    public record Content(Resource resource, String mimeType, long byteSize) {}
}
