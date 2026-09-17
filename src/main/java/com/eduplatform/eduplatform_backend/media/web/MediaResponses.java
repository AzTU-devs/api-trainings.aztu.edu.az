package com.eduplatform.eduplatform_backend.media.web;

import com.eduplatform.eduplatform_backend.media.service.MediaService;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * Builds the response that streams stored bytes back.
 *
 * <p>Every header here exists to stop an uploaded file from behaving like an active document on
 * the API origin, which the admin portal proxies same-origin: the content type is the one
 * validated at upload time rather than anything the request asks for, {@code nosniff} stops a
 * browser from upgrading it to something executable, and anything that is not an image or a video
 * is forced to download instead of being rendered in place.
 */
final class MediaResponses {

    private static final String X_CONTENT_TYPE_OPTIONS = "X-Content-Type-Options";

    private MediaResponses() {}

    static ResponseEntity<Resource> stream(MediaService.Content content, String cacheControl) {
        ContentDisposition disposition = (content.inline()
                ? ContentDisposition.inline()
                : ContentDisposition.attachment())
                .filename(content.filename())
                .build();

        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(content.mimeType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(X_CONTENT_TYPE_OPTIONS, "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, cacheControl);

        // A stored object is immutable — a replacement upload gets a new id — so its content hash
        // is a perfect strong ETag, and Spring turns a matching If-None-Match into a 304.
        if (content.checksumSha256() != null && !content.checksumSha256().isBlank()) {
            response.eTag("\"" + content.checksumSha256() + "\"");
        }
        return response.body(content.resource());
    }
}
