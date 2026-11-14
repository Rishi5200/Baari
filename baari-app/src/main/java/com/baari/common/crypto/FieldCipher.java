package com.baari.common.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts single column values with AES-256-GCM (PLAN §9.4). GCM gives confidentiality and
 * tamper detection: a changed byte makes decryption fail instead of returning garbage.
 *
 * <p>Stored layout: {@code version (1 byte) | IV (12 bytes) | ciphertext + 16-byte tag}. The IV
 * is random for every value, so the same phone number encrypts differently each time and the
 * column can't be used to spot repeat patients (the HMAC hash column exists for that). The
 * version byte leaves room to rotate the key later without guessing which key a row used.
 */
public final class FieldCipher {

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte VERSION = 1;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int KEY_BYTES = 32;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    /** @param key exactly 32 bytes (AES-256) */
    public FieldCipher(byte[] key) {
        if (key == null || key.length != KEY_BYTES) {
            throw new IllegalArgumentException("data key must be exactly 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    public byte[] encrypt(String plaintext) {
        byte[] iv = new byte[IV_BYTES];
        random.nextBytes(iv);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + IV_BYTES + sealed.length)
                    .put(VERSION)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM encryption failed", e);
        }
    }

    /** @throws IllegalStateException if the value was changed, truncated, or made with another key */
    public String decrypt(byte[] stored) {
        if (stored == null || stored.length < 1 + IV_BYTES + TAG_BITS / 8 || stored[0] != VERSION) {
            throw new IllegalStateException("not a value written by this cipher");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, stored, 1, IV_BYTES));
            byte[] plain = cipher.doFinal(stored, 1 + IV_BYTES, stored.length - 1 - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Don't pass on the cause's message: keep the error free of any data.
            throw new IllegalStateException("AES-GCM decryption failed (wrong key or tampered value)");
        }
    }
}
