package com.baari.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 of an E.164 number with a secret pepper (PLAN §9.4). The same number always gives
 * the same hash, which is what the "one active token per phone" unique index needs.
 *
 * <p>A plain SHA-256 would not do: there are only about 10^10 Indian mobile numbers, so anyone
 * with the database could hash them all in minutes. Without the pepper (kept out of the
 * database, in an env var) the hashes can't be reversed that way.
 */
public final class PhoneHasher {

    private static final String ALGORITHM = "HmacSHA256";
    private static final int MIN_PEPPER_BYTES = 32;

    private final SecretKeySpec pepper;

    /** @param pepper at least 32 bytes of secret random data */
    public PhoneHasher(byte[] pepper) {
        if (pepper == null || pepper.length < MIN_PEPPER_BYTES) {
            throw new IllegalArgumentException("phone pepper must be at least 32 bytes");
        }
        this.pepper = new SecretKeySpec(pepper, ALGORITHM);
    }

    /** 32 bytes, stored in {@code token.phone_hash}. */
    public byte[] hash(PhoneNumber phone) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(pepper);
            return mac.doFinal(phone.e164().getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA256 is required by every JVM", e);
        }
    }
}
