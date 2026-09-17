package com.eduplatform.eduplatform_backend.media.web;

import com.eduplatform.eduplatform_backend.media.service.MediaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Anonymous read of public media. This is where course thumbnails and trailers are served from:
 * the marketing site embeds them as plain relative URLs, so they have to work with no token at
 * all, and a media id that is not public must look exactly like one that does not exist.
 */
@RestController
@RequestMapping("/api/public/media")
@Tag(name = "Public — Media")
public class MediaPublicController {

    /**
     * Safe for any shared cache: a media row's bytes never change (a replacement upload gets a new
     * id), and only assets that are already public reach this endpoint.
     */
    private static final String PUBLIC_CACHE = "public, max-age=86400, immutable";

    private final MediaService service;

    public MediaPublicController(MediaService service) {
        this.service = service;
    }

    @GetMapping("/{id}/content")
    @Operation(summary = "Stream a public media asset (course thumbnail or trailer)", security = {})
    public ResponseEntity<Resource> content(@PathVariable UUID id) {
        return MediaResponses.stream(service.loadPublicContent(id), PUBLIC_CACHE);
    }
}
