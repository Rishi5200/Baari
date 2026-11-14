package com.baari.common.crypto;

/** Masked forms of personal data, for staff screens and the rare log line that needs one. */
public final class Masking {

    private static final String DOTS = "••••••";

    private Masking() {}

    /**
     * {@code +919876543210} or a stored last-4 {@code 3210} both become {@code ••••••3210}. Inputs
     * with fewer than four digits show no digits at all; {@code null} becomes an empty string.
     */
    public static String phone(String phoneOrLast4) {
        if (phoneOrLast4 == null) {
            return "";
        }
        String digits = phoneOrLast4.replaceAll("\\D", "");
        if (digits.length() < 4) {
            return DOTS;
        }
        return DOTS + digits.substring(digits.length() - 4);
    }
}
