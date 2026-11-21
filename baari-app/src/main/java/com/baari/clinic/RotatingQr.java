package com.baari.clinic;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signatures for rotating QR codes (PLAN §9.3). The TV shows a join link carrying a time window and
 * {@code HMAC-SHA256(qr_secret, queueId|window)}; a photo of the screen stops working after a
 * minute or two, so nobody can join from home with an old picture. Pure: the caller supplies the
 * time and the secret.
 */
public final class RotatingQr {

    /** One window lasts this many seconds. */
    public static final long WINDOW_SECONDS = 60;

    /** Bytes of the HMAC kept in the link: 128 bits is far beyond guessing within a minute. */
    static final int SIGNATURE_BYTES = 16;

    private RotatingQr() {}

    /** The window an instant falls in. */
    public static long window(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), WINDOW_SECONDS);
    }

    /** When a link of this window stops being accepted: the end of the window after it. */
    public static Instant validUntil(long window) {
        return Instant.ofEpochSecond((window + 2) * WINDOW_SECONDS);
    }

    static String sign(byte[] secret, UUID queueId, long window) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] full = mac.doFinal((queueId + "|" + window).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, SIGNATURE_BYTES));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 unavailable", e);
        }
    }
}
