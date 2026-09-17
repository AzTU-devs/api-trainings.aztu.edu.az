package com.eduplatform.eduplatform_backend.media.upload;

import com.eduplatform.eduplatform_backend.common.error.AppException;
import org.springframework.http.HttpStatus;

/**
 * Typed rejections for an upload. 415 means "we do not accept this kind of file", 413 means "we
 * accept it but not this big" — the codes are stable so the portal can map them to a message.
 */
public final class UploadErrors {

    private UploadErrors() {}

    public static AppException unsupportedType(String declaredMime) {
        return new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Files of type " + declaredMime + " are not accepted. Allowed types: "
                        + AllowedMediaType.allowedMimes());
    }

    public static AppException unrecognizedContent() {
        return new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNRECOGNIZED_FILE_CONTENT",
                "The file's contents do not match any accepted format. Allowed types: "
                        + AllowedMediaType.allowedMimes());
    }

    public static AppException typeMismatch(String declaredMime, String detectedMime) {
        return new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "MEDIA_TYPE_MISMATCH",
                "The uploaded bytes are " + detectedMime + ", not the declared " + declaredMime);
    }

    public static AppException notAVideo(String detectedMime) {
        return new AppException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "NOT_A_VIDEO",
                "A video file is required, but this is " + detectedMime);
    }

    /** Thrown both up front (declared size) and mid-stream (actual bytes written). */
    public static AppException tooLarge(long maxBytes) {
        return new AppException(HttpStatus.PAYLOAD_TOO_LARGE, "UPLOAD_TOO_LARGE",
                "File is larger than the " + (maxBytes / (1024L * 1024L)) + " MB limit for this type");
    }
}
