package com.eduplatform.eduplatform_backend.media.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.media.service.MediaService;
import com.eduplatform.eduplatform_backend.media.web.dto.MediaFileDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/**
 * Binary media upload & retrieval. Any authenticated user may upload (e.g. a
 * tutor attaching a lesson video/PDF); the returned {@code id} is then stored
 * on the owning entity (lesson {@code videoMediaId}, course thumbnail, etc.).
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
    @Operation(summary = "Upload a media file (multipart) and return its metadata")
    public ResponseEntity<ApiResponse<MediaFileDto>> upload(@RequestParam("file") MultipartFile file,
                                                            @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.upload(file, me.userId())));
    }

    @GetMapping("/{id}/content")
    @Operation(summary = "Stream the raw bytes of a media file (access-controlled)")
    public ResponseEntity<Resource> content(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        MediaService.Content c = service.loadContent(id, me);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(c.mimeType()))
                .contentLength(c.byteSize())
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=3600")
                .body(c.resource());
    }
}
