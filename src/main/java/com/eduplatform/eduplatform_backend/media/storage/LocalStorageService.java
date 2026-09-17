package com.eduplatform.eduplatform_backend.media.storage;

import com.eduplatform.eduplatform_backend.common.enums.MediaStorage;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import com.eduplatform.eduplatform_backend.media.upload.UploadErrors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Local-filesystem storage. Active when {@code app.storage.provider=LOCAL}
 * (the default). Files are written under {@code app.storage.local.base-dir}
 * with an opaque, sharded object key; the SHA-256 is computed while streaming.
 */
@Service
public class LocalStorageService implements StorageService {

    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final Path baseDir;

    public LocalStorageService(@Value("${app.storage.local.base-dir:/opt/uploads}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public Stored store(InputStream in, String extension, long maxBytes) {
        if (in == null) {
            throw Errors.badRequest("EMPTY_FILE", "No content to store");
        }
        String objectKey = newObjectKey(extension);
        Path target = resolve(objectKey);
        boolean kept = false;
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = copyCapped(in, target, digest, maxBytes);
            if (size == 0) {
                throw Errors.badRequest("EMPTY_FILE", "Uploaded file is empty");
            }
            kept = true;
            return new Stored(objectKey, HexFormat.of().formatHex(digest.digest()), size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw Errors.unprocessable("STORAGE_WRITE_FAILED", "Could not store uploaded file");
        } finally {
            // No media row references this key yet, so a partial write left behind by a rejected
            // upload (over the cap, empty, client hung up) would never be collected by anything.
            if (!kept) {
                delete(objectKey);
            }
        }
    }

    @Override
    public Resource load(String objectKey) {
        Path path = resolve(objectKey);
        // NOFOLLOW_LINKS: a symlink under the base dir would otherwise be a way to read any file
        // the service user can reach, which is exactly what the traversal guard exists to prevent.
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw Errors.notFound("MEDIA_NOT_FOUND", "Stored object no longer exists");
        }
        try {
            return new UrlResource(path.toUri());
        } catch (IOException e) {
            throw Errors.notFound("MEDIA_NOT_FOUND", "Stored object could not be read");
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Files.deleteIfExists(resolve(objectKey));
        } catch (IOException ignored) {
            // best-effort
        }
    }

    @Override
    public MediaStorage type() {
        return MediaStorage.LOCAL;
    }

    /**
     * Stream to disk while hashing, refusing to write more than {@code maxBytes}. The cap is
     * applied as the bytes arrive because a declared body length is absent on a chunked upload and
     * untrustworthy otherwise; the alternative is writing the whole file before measuring it.
     */
    private static long copyCapped(InputStream in, Path target, MessageDigest digest, long maxBytes)
            throws IOException {
        long total = 0;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        try (OutputStream file = Files.newOutputStream(target, StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             DigestOutputStream out = new DigestOutputStream(file, digest)) {
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (maxBytes > 0 && total > maxBytes) {
                    throw UploadErrors.tooLarge(maxBytes);
                }
                out.write(buffer, 0, read);
            }
        }
        return total;
    }

    /** Opaque, sharded key. The extension comes from the validated type, never the client. */
    private static String newObjectKey(String extension) {
        String id = UUID.randomUUID().toString();
        // Shard by the first two hex chars to keep directories small.
        return id.substring(0, 2) + "/" + id + (extension == null ? "" : extension);
    }

    /** Resolve an object key under the base dir, guarding against path traversal. */
    private Path resolve(String objectKey) {
        if (objectKey == null || objectKey.isBlank()) {
            throw Errors.badRequest("INVALID_OBJECT_KEY", "Illegal storage path");
        }
        Path path = baseDir.resolve(objectKey).normalize();
        // startsWith compares whole path elements, so a sibling directory whose name merely begins
        // with the base dir's name ("/opt/uploads-evil") does not pass either.
        if (!path.startsWith(baseDir) || path.equals(baseDir)) {
            throw Errors.badRequest("INVALID_OBJECT_KEY", "Illegal storage path");
        }
        return path;
    }
}
