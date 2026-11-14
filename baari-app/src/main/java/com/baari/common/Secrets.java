package com.baari.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random secrets and their hashes (TV display keys, QR HMAC keys). */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Secrets() {}

    public static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }

    /** URL-safe random token with {@code bytes * 8} bits of entropy. */
    public static String randomToken(int bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes(bytes));
    }

    /**
     * SHA-256 as hex. Fine for hashing <em>high-entropy random</em> keys before storing them: they
     * can't be brute-forced, so a slow hash like BCrypt (needed for human passwords) adds nothing.
     */
    public static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JVM", e);
        }
    }
}
