package com.baari.queue.internal;

import java.security.SecureRandom;

/**
 * The secret in a patient's token URL (PLAN §3: 22-char base62). Whoever knows it can see and
 * cancel the token, so it must be unguessable: 22 base62 characters carry about 131 bits.
 * Base62 (letters and digits only) survives being read out, typed or put in an SMS without
 * escaping.
 */
public final class PublicCodes {

    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int LENGTH = 22;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PublicCodes() {}

    public static String newCode() {
        StringBuilder code = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            // nextInt(bound) is uniform, so every character is equally likely (no modulo bias).
            code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return code.toString();
    }
}
