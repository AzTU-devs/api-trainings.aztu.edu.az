package com.eduplatform.eduplatform_backend.media.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.media.service.MediaService;
import com.eduplatform.eduplatform_backend.media.upload.AllowedMediaType;
import com.eduplatform.eduplatform_backend.media.web.dto.MediaFileDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Binary media upload & retrieval. Uploading is for tutors and staff (e.g. a tutor attaching a
 * lesson PDF, an admin a room photo); the returned {@code id} is then stored on the owning entity
 * (lesson {@code videoMediaId}, course thumbnail, etc.). That restriction lives in SecurityConfig's
 * filter chain rather than here, because by the time this controller could refuse a request Tomcat
 * has already spooled the whole multipart body to disk. Reading is open to any signed-in account,
 * subject to MediaService's per-file access rules.
 *
 * <p>Uploads are also restricted to the {@link AllowedMediaType} allowlist and verified against
 * the file's own leading bytes, so no role can store an executable or an SVG as an "image".
 */
@RestController
@RequestMapping("/api/media")
@Tag(name = "Media")
public class MediaController {

    private final MediaService service;

    public MediaController(MediaService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Upload a media file (multipart) and return its metadata",
            description = "Accepts images (JPEG, PNG, WebP, GIF, AVIF), video (MP4, WebM, QuickTime) and PDF. "
                    + "SVG is not accepted. The declared content type must match the file's own signature, "
                    + "and each kind has its own size limit.")
    public ResponseEntity<ApiResponse<MediaFileDto>> upload(
            // Not required at the binding layer: a missing part is a 400 EMPTY_FILE from the
            // service, not the 500 that Spring's own missing-parameter failure would produce.
            @RequestParam(value = "file", required = false) MultipartFile file,
            @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.upload(file, me.userId())));
    }

    @GetMapping("/{id}/content")
    @Operation(summary = "Stream the raw bytes of a media file (access-controlled)")
    public ResponseEntity<Resource> content(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        return MediaResponses.stream(service.loadContent(id, me), "private, max-age=3600");
    }
}
