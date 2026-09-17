package com.eduplatform.eduplatform_backend.media.storage;

import com.eduplatform.eduplatform_backend.common.enums.MediaStorage;
import org.springframework.core.io.Resource;

import java.io.InputStream;

/** Pluggable binary storage backend (local disk today; S3/CDN later). */
public interface StorageService {

    /**
     * Persist the bytes of {@code in} under a newly generated, opaque object key ending in
     * {@code extension}, and return a handle to what was stored.
     *
     * <p>Callers pass the extension of the type they have already validated — never one taken from
     * the client's filename — and {@code maxBytes} as a hard ceiling that is enforced while the
     * bytes stream, so an oversized body is cut off instead of being buffered and measured
     * afterwards. Nothing partial survives a rejection.
     *
     * @param maxBytes ceiling in bytes; {@code <= 0} means no ceiling
     */
    Stored store(InputStream in, String extension, long maxBytes);

    /** Resolve a previously-stored object to a readable resource. */
    Resource load(String objectKey);

    /** Remove a stored object (best-effort). */
    void delete(String objectKey);

    /** Which {@link MediaStorage} backend this implementation represents. */
    MediaStorage type();

    /** Result of a successful {@link #store(InputStream, String, long)}. */
    record Stored(String objectKey, String sha256, long size) {}
}
