package com.eduplatform.eduplatform_backend.media.storage;

import com.eduplatform.eduplatform_backend.common.enums.MediaStorage;
import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

/** Pluggable binary storage backend (local disk today; S3/CDN later). */
public interface StorageService {

    /** Persist the uploaded bytes and return a handle to what was stored. */
    Stored store(MultipartFile file);

    /** Persist a raw byte stream (e.g. a chunked/streamed video PUT) and return a handle. */
    Stored store(java.io.InputStream in, String originalFilename);

    /** Resolve a previously-stored object to a readable resource. */
    Resource load(String objectKey);

    /** Remove a stored object (best-effort). */
    void delete(String objectKey);

    /** Which {@link MediaStorage} backend this implementation represents. */
    MediaStorage type();

    /** Result of a successful {@link #store(MultipartFile)}. */
    record Stored(String objectKey, String sha256, long size) {}
}
