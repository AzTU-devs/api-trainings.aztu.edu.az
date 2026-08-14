package com.eduplatform.eduplatform_backend.media.storage;

import com.eduplatform.eduplatform_backend.common.enums.MediaStorage;
import com.eduplatform.eduplatform_backend.common.error.Errors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
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

    private final Path baseDir;

    public LocalStorageService(@Value("${app.storage.local.base-dir:./var/uploads}") String baseDir) {
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    @Override
    public Stored store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw Errors.badRequest("EMPTY_FILE", "Uploaded file is empty");
        }
        String ext = extensionOf(file.getOriginalFilename());
        String id = UUID.randomUUID().toString();
        // Shard by the first two hex chars to keep directories small.
        String objectKey = id.substring(0, 2) + "/" + id + ext;
        Path target = resolve(objectKey);

        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (InputStream in = file.getInputStream();
                 DigestInputStream dis = new DigestInputStream(in, digest)) {
                size = Files.copy(dis, target, StandardCopyOption.REPLACE_EXISTING);
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new Stored(objectKey, sha256, size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw Errors.unprocessable("STORAGE_WRITE_FAILED", "Could not store uploaded file");
        }
    }

    @Override
    public Stored store(InputStream in, String originalFilename) {
        if (in == null) {
            throw Errors.badRequest("EMPTY_FILE", "No content to store");
        }
        String ext = extensionOf(originalFilename);
        String id = UUID.randomUUID().toString();
        String objectKey = id.substring(0, 2) + "/" + id + ext;
        Path target = resolve(objectKey);
        try {
            Files.createDirectories(target.getParent());
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size;
            try (DigestInputStream dis = new DigestInputStream(in, digest)) {
                size = Files.copy(dis, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (size == 0) {
                Files.deleteIfExists(target);
                throw Errors.badRequest("EMPTY_FILE", "Uploaded stream was empty");
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            return new Stored(objectKey, sha256, size);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        } catch (IOException e) {
            throw Errors.unprocessable("STORAGE_WRITE_FAILED", "Could not store uploaded stream");
        }
    }

    @Override
    public Resource load(String objectKey) {
        Path path = resolve(objectKey);
        if (!Files.exists(path)) {
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

    /** Resolve an object key under the base dir, guarding against path traversal. */
    private Path resolve(String objectKey) {
        Path path = baseDir.resolve(objectKey).normalize();
        if (!path.startsWith(baseDir)) {
            throw Errors.badRequest("INVALID_OBJECT_KEY", "Illegal storage path");
        }
        return path;
    }

    private static String extensionOf(String filename) {
        String ext = StringUtils.getFilenameExtension(filename);
        return (ext == null || ext.isBlank()) ? "" : "." + ext.toLowerCase();
    }
}
