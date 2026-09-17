package com.eduplatform.eduplatform_backend.media.upload;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * The gate every uploaded byte passes through: an allowlist of types, a per-kind size ceiling,
 * and a signature check so the bytes have to agree with what the client claimed.
 *
 * <p>The ceilings are configurable because they must stay in step with the reverse proxy's
 * {@code client_max_body_size} (550m, sized for the 512 MB video cap plus multipart overhead) and
 * with {@code spring.servlet.multipart.max-file-size}; the defaults here are those same numbers,
 * so the service is safe even if the properties file has not been updated.
 */
@Component
public class UploadPolicy {

    /**
     * Leading bytes read for signature detection. 128 covers the longest thing inspected — an
     * ISO base media {@code ftyp} box with a long compatible-brand list.
     */
    public static final int SNIFF_BYTES = 128;

    /**
     * What a client sends when it has no idea what the file is (a raw {@code PUT} from an
     * XHR, curl without {@code -H}). Treated as "no claim" rather than as a claim we reject,
     * so detection alone decides.
     */
    private static final String GENERIC_MIME = "application/octet-stream";

    /** Declared types are echoed back in errors; do not let a client dictate a huge message. */
    private static final int MAX_MIME_LENGTH = 120;

    private final Map<UploadKind, Long> ceilings = new EnumMap<>(UploadKind.class);

    public UploadPolicy(@Value("${app.uploads.max-image-mb:10}") int maxImageMb,
                        @Value("${app.uploads.max-video-mb:512}") int maxVideoMb,
                        @Value("${app.uploads.max-document-mb:25}") int maxDocumentMb) {
        ceilings.put(UploadKind.IMAGE, megabytes(maxImageMb));
        ceilings.put(UploadKind.VIDEO, megabytes(maxVideoMb));
        ceilings.put(UploadKind.DOCUMENT, megabytes(maxDocumentMb));
    }

    public long maxBytes(UploadKind kind) {
        return ceilings.get(kind);
    }

    /**
     * Validate one upload and return the type it will be stored as.
     *
     * <p>{@code declaredSizeBytes} is a pre-flight check only: multipart reports a size, a chunked
     * {@code PUT} reports {@code -1}, and either can lie. The cap that actually stops an oversized
     * body is the one the storage layer applies as the bytes arrive.
     */
    public AllowedMediaType validate(String declaredMime, byte[] head, long declaredSizeBytes) {
        String declared = normalizeMime(declaredMime);
        // A blank or generic declaration means the client does not know what it is holding, which is
        // fine — the bytes decide. Anything else is a claim, and a claim must be a type we accept.
        AllowedMediaType claimed = declared.isEmpty() || GENERIC_MIME.equals(declared)
                ? null
                : AllowedMediaType.fromMime(declared).orElseThrow(() -> UploadErrors.unsupportedType(declared));

        AllowedMediaType detected = AllowedMediaType.detect(head)
                .orElseThrow(UploadErrors::unrecognizedContent);
        if (claimed != null && claimed != detected) {
            throw UploadErrors.typeMismatch(declared, detected.mime());
        }

        requireWithinLimit(detected.kind(), declaredSizeBytes);
        return detected;
    }

    /** As {@link #validate}, for an endpoint that only ever accepts video. */
    public AllowedMediaType validateVideo(String declaredMime, byte[] head, long declaredSizeBytes) {
        AllowedMediaType type = validate(declaredMime, head, declaredSizeBytes);
        if (type.kind() != UploadKind.VIDEO) {
            throw UploadErrors.notAVideo(type.mime());
        }
        return type;
    }

    /**
     * Declared-type check for {@code /videos/init}, which has no bytes to sniff yet. A client that
     * declares nothing gets MP4 as a placeholder; the real type is settled when the bytes arrive.
     */
    public AllowedMediaType declaredVideoType(String declaredMime) {
        String declared = normalizeMime(declaredMime);
        if (declared.isEmpty() || GENERIC_MIME.equals(declared)) {
            return AllowedMediaType.MP4;
        }
        AllowedMediaType type = AllowedMediaType.fromMime(declared)
                .orElseThrow(() -> UploadErrors.unsupportedType(declared));
        if (type.kind() != UploadKind.VIDEO) {
            throw UploadErrors.notAVideo(type.mime());
        }
        return type;
    }

    /** Reject a known size that is already over the ceiling. Unknown sizes ({@code <= 0}) pass. */
    public void requireWithinLimit(UploadKind kind, long sizeBytes) {
        long max = maxBytes(kind);
        if (sizeBytes > max) {
            throw UploadErrors.tooLarge(max);
        }
    }

    /** Strip parameters ({@code ;charset=…}), lower-case, and bound the length. */
    private static String normalizeMime(String mime) {
        if (mime == null) {
            return "";
        }
        String value = mime;
        int parameter = value.indexOf(';');
        if (parameter >= 0) {
            value = value.substring(0, parameter);
        }
        value = value.trim().toLowerCase(Locale.ROOT);
        return value.length() > MAX_MIME_LENGTH ? value.substring(0, MAX_MIME_LENGTH) : value;
    }

    private static long megabytes(int mb) {
        return Math.max(1L, mb) * 1024L * 1024L;
    }
}
