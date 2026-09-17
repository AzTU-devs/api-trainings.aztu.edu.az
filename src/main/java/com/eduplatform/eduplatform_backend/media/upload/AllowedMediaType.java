package com.eduplatform.eduplatform_backend.media.upload;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The complete set of binary types this platform stores, each paired with the extension it is
 * stored under and the check that proves a file really is that type.
 *
 * <p>This is an allowlist on purpose. A blocklist of "dangerous" extensions is unwinnable: a
 * stored object is reachable at {@code /api/media/{id}/content}, which the admin portal proxies
 * same-origin, so anything a browser is willing to execute there runs with the portal's origin
 * and can read its tokens.
 *
 * <p>{@code image/svg+xml} is deliberately absent and must stay absent. An SVG is an XML document
 * that may carry {@code <script>} and inline event handlers — an active document wearing an
 * image's name, and the one image type that cannot be served safely from an origin that matters.
 * The same reasoning rules out text/html, application/xhtml+xml, application/xml and every
 * flavor of javascript. Do not add them back.
 */
public enum AllowedMediaType {

    JPEG("image/jpeg", UploadKind.IMAGE, ".jpg", FileSignatures::isJpeg),
    PNG("image/png", UploadKind.IMAGE, ".png", FileSignatures::isPng),
    GIF("image/gif", UploadKind.IMAGE, ".gif", FileSignatures::isGif),
    WEBP("image/webp", UploadKind.IMAGE, ".webp", FileSignatures::isWebp),
    // AVIF is declared before MP4 so it wins detection: both are ISO base media files and only
    // the ftyp brand list separates a still image from a movie.
    AVIF("image/avif", UploadKind.IMAGE, ".avif", FileSignatures::isAvif),

    MP4("video/mp4", UploadKind.VIDEO, ".mp4", FileSignatures::isMp4),
    WEBM("video/webm", UploadKind.VIDEO, ".webm", FileSignatures::isWebm),
    QUICKTIME("video/quicktime", UploadKind.VIDEO, ".mov", FileSignatures::isQuickTime),

    PDF("application/pdf", UploadKind.DOCUMENT, ".pdf", FileSignatures::isPdf);

    private final String mime;
    private final UploadKind kind;
    private final String extension;
    private final Predicate<byte[]> signature;

    AllowedMediaType(String mime, UploadKind kind, String extension, Predicate<byte[]> signature) {
        this.mime = mime;
        this.kind = kind;
        this.extension = extension;
        this.signature = signature;
    }

    public String mime() {
        return mime;
    }

    public UploadKind kind() {
        return kind;
    }

    /** The extension the object is stored under — derived here, never taken from the client. */
    public String extension() {
        return extension;
    }

    /**
     * What the given leading bytes actually are, if we accept it. Detection drives both the
     * decision and the stored type, because the declared content type is client-controlled.
     */
    public static Optional<AllowedMediaType> detect(byte[] head) {
        if (head == null || head.length == 0) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(t -> t.signature.test(head)).findFirst();
    }

    /** An accepted type by its exact MIME string (expects it already trimmed and lower-cased). */
    public static Optional<AllowedMediaType> fromMime(String mime) {
        if (mime == null || mime.isBlank()) {
            return Optional.empty();
        }
        String normalized = mime.toLowerCase(Locale.ROOT);
        return Arrays.stream(values()).filter(t -> t.mime.equals(normalized)).findFirst();
    }

    /** The allowlist, for error messages. */
    public static String allowedMimes() {
        return Arrays.stream(values()).map(AllowedMediaType::mime).collect(Collectors.joining(", "));
    }
}
