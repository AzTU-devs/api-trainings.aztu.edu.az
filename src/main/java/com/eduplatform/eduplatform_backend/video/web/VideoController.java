package com.eduplatform.eduplatform_backend.video.web;

import com.eduplatform.eduplatform_backend.common.security.AuthenticatedPrincipal;
import com.eduplatform.eduplatform_backend.common.security.CurrentUser;
import com.eduplatform.eduplatform_backend.common.web.ApiResponse;
import com.eduplatform.eduplatform_backend.common.web.PageResponse;
import com.eduplatform.eduplatform_backend.video.service.VideoService;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoAssetDto;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoCompleteRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitRequest;
import com.eduplatform.eduplatform_backend.video.web.dto.VideoInitResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.UUID;

/**
 * Tutor video library. Upload flow: {@code POST /init} → {@code PUT /{id}/content}
 * (raw bytes) → {@code POST /{id}/complete}. All endpoints require the tutor
 * {@code course:create} authority.
 */
@RestController
@RequestMapping("/api/videos")
@Tag(name = "Videos")
@PreAuthorize("hasAuthority('course:create')")
public class VideoController {

    private final VideoService service;

    public VideoController(VideoService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "List my uploaded videos")
    public ApiResponse<PageResponse<VideoAssetDto>> list(@CurrentUser AuthenticatedPrincipal me, Pageable pageable) {
        return ApiResponse.ok(PageResponse.of(service.list(me.userId(), pageable)));
    }

    @PostMapping("/init")
    @Operation(summary = "Begin a video upload; returns the URL to PUT bytes to")
    public ApiResponse<VideoInitResponse> init(@Valid @RequestBody VideoInitRequest req,
                                               @CurrentUser AuthenticatedPrincipal me) {
        return ApiResponse.ok(service.init(me.userId(), req));
    }

    @PutMapping("/{id}/content")
    @Operation(summary = "Upload the raw video bytes for a started upload",
            description = "The body must be one of MP4, WebM or QuickTime and is refused past the "
                    + "configured video size limit, mid-stream if necessary.")
    public ResponseEntity<Void> upload(@PathVariable UUID id,
                                       @CurrentUser AuthenticatedPrincipal me,
                                       HttpServletRequest request) throws IOException {
        // getContentLengthLong() is -1 on a chunked upload; the service treats that as "unknown"
        // and relies on the cap the storage layer applies while the bytes arrive.
        service.storeBytes(me.userId(), id, request.getInputStream(), request.getContentType(),
                request.getContentLengthLong());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/complete")
    @Operation(summary = "Finalize a video upload")
    public ResponseEntity<ApiResponse<VideoAssetDto>> complete(@PathVariable UUID id,
                                                               @Valid @RequestBody(required = false) VideoCompleteRequest req,
                                                               @CurrentUser AuthenticatedPrincipal me) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.complete(me.userId(), id, req)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a video")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @CurrentUser AuthenticatedPrincipal me) {
        service.delete(me.userId(), id);
        return ResponseEntity.noContent().build();
    }
}
