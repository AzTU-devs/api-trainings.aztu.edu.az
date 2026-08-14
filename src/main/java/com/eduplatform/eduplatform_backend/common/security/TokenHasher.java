package com.eduplatform.eduplatform_backend.common.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Generates opaque random tokens and the SHA-256 hex digest stored in the DB.
 * Raw tokens are returned exactly once to the caller and never persisted.
 */
public final class TokenHasher {

    private static final SecureRandom RNG = new SecureRandom();
    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private TokenHasher() {}

    /** URL-safe base64 token of {@code byteLen} random bytes. 32 bytes ≈ 256 bits of entropy. */
    public static String randomToken(int byteLen) {
        byte[] buf = new byte[byteLen];
        RNG.nextBytes(buf);
        return URL.encodeToString(buf);
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
