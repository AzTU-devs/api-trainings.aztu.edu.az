package com.eduplatform.eduplatform_backend.media.upload;

import java.util.Locale;
import java.util.Set;

/**
 * Magic-byte checks for the types on the {@link AllowedMediaType} allowlist.
 *
 * <p>The declared content type of an upload is whatever the client typed into a header, so it
 * proves nothing; these checks are what actually decide what a file is. They only ever read the
 * first {@link UploadPolicy#SNIFF_BYTES} bytes, and they are deliberately conservative: a file
 * that does not match anything here is rejected rather than stored as "probably fine".
 *
 * <p>Lives outside the enum because an enum constant's arguments cannot reference the enum's own
 * static fields (illegal forward reference), and the brand tables below must be built once.
 */
final class FileSignatures {

    /** libavif writes {@code avif}; HEIF-derived tools write {@code mif1}/{@code miaf} first. */
    private static final Set<String> AVIF_BRANDS = Set.of("avif", "avis", "mif1", "miaf", "mia1");

    private static final Set<String> MP4_BRANDS = Set.of(
            "isom", "iso2", "iso4", "iso5", "iso6", "mp41", "mp42", "mp71",
            "avc1", "dash", "mmp4", "m4v ", "msnv", "ndas", "f4v ");

    private static final Set<String> QUICKTIME_BRANDS = Set.of("qt  ");

    /** A .mov written before the ftyp box existed starts straight with a top-level atom. */
    private static final Set<String> LEGACY_QUICKTIME_ATOMS =
            Set.of("moov", "mdat", "free", "skip", "wide", "pnot");

    private FileSignatures() {}

    static boolean isJpeg(byte[] head) {
        return startsWith(head, 0xFF, 0xD8, 0xFF);
    }

    static boolean isPng(byte[] head) {
        return startsWith(head, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
    }

    static boolean isGif(byte[] head) {
        return ascii(head, 0, "GIF87a") || ascii(head, 0, "GIF89a");
    }

    /** RIFF container with a WEBP form type; the four bytes between the two are the length. */
    static boolean isWebp(byte[] head) {
        return ascii(head, 0, "RIFF") && ascii(head, 8, "WEBP");
    }

    static boolean isAvif(byte[] head) {
        return hasIsoBrand(head, AVIF_BRANDS);
    }

    static boolean isMp4(byte[] head) {
        return hasIsoBrand(head, MP4_BRANDS);
    }

    /**
     * EBML header. Shared with Matroska (.mkv), which we accept as WebM rather than reject: the
     * two are the same container and neither is something a browser will ever execute.
     */
    static boolean isWebm(byte[] head) {
        return startsWith(head, 0x1A, 0x45, 0xDF, 0xA3);
    }

    static boolean isQuickTime(byte[] head) {
        return hasIsoBrand(head, QUICKTIME_BRANDS) || LEGACY_QUICKTIME_ATOMS.contains(fourCc(head, 4));
    }

    static boolean isPdf(byte[] head) {
        return ascii(head, 0, "%PDF-");
    }

    // ── primitives ──────────────────────────────────────────────────────

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    private static boolean ascii(byte[] head, int offset, String literal) {
        if (head.length < offset + literal.length()) {
            return false;
        }
        for (int i = 0; i < literal.length(); i++) {
            if ((head[offset + i] & 0xFF) != literal.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether an ISO base media file declares any of {@code brands}. The whole brand list is
     * scanned — major brand at offset 8 plus the compatible brands after it — because muxers
     * disagree about which one comes first, and an AVIF still image and an MP4 movie are the
     * same container distinguished only by these four-character codes.
     */
    private static boolean hasIsoBrand(byte[] head, Set<String> brands) {
        if (!ascii(head, 4, "ftyp")) {
            return false;
        }
        int declaredEnd = boxSize(head);
        int end = declaredEnd <= 0 ? head.length : Math.min(declaredEnd, head.length);
        for (int offset = 8; offset + 4 <= end; offset += 4) {
            if (brands.contains(fourCc(head, offset))) {
                return true;
            }
        }
        return false;
    }

    /** Big-endian length of the leading box, or 0 when it is unreadable or nonsensical. */
    private static int boxSize(byte[] head) {
        if (head.length < 4) {
            return 0;
        }
        long size = ((long) (head[0] & 0xFF) << 24) | ((long) (head[1] & 0xFF) << 16)
                | ((long) (head[2] & 0xFF) << 8) | (head[3] & 0xFF);
        return size < 8 || size > Integer.MAX_VALUE ? 0 : (int) size;
    }

    /** The four-character code at {@code offset}, lower-cased; empty when out of range. */
    private static String fourCc(byte[] head, int offset) {
        if (head == null || head.length < offset + 4) {
            return "";
        }
        StringBuilder code = new StringBuilder(4);
        for (int i = offset; i < offset + 4; i++) {
            code.append((char) (head[i] & 0xFF));
        }
        return code.toString().toLowerCase(Locale.ROOT);
    }
}
