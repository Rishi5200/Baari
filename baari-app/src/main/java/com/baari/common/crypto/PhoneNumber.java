package com.baari.common.crypto;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A phone number in E.164 form ({@code +919876543210}). Numbers without a country code are taken
 * as Indian (PLAN §3).
 *
 * <p>{@link #toString()} is masked, so a number that ends up in a log line or an exception
 * message by accident doesn't leak.
 */
public record PhoneNumber(String e164) {

    /** E.164: a plus, a country code not starting with 0, at most 15 digits in all. */
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /** Indian mobile numbers are 10 digits starting with 6–9; landlines can't receive alerts. */
    private static final Pattern INDIAN_MOBILE = Pattern.compile("^[6-9]\\d{9}$");

    private static final String INDIA = "+91";

    public PhoneNumber {
        Objects.requireNonNull(e164, "e164");
        if (!E164.matcher(e164).matches()) {
            throw new InvalidPhoneNumberException();
        }
        if (e164.startsWith(INDIA)
                && !INDIAN_MOBILE.matcher(e164.substring(INDIA.length())).matches()) {
            throw new InvalidPhoneNumberException();
        }
    }

    /**
     * Reads a number the way people type it: spaces, dashes, dots and brackets are ignored, and
     * these forms are accepted for India: {@code 98765 43210}, {@code 098765 43210},
     * {@code 91 98765 43210}, {@code +91 98765 43210}, {@code 0091 98765 43210}. Any other
     * country needs a {@code +} or {@code 00} prefix.
     *
     * @throws InvalidPhoneNumberException if the input isn't a valid number
     */
    public static PhoneNumber parse(String raw) {
        if (raw == null) {
            throw new InvalidPhoneNumberException();
        }
        String compact = raw.replaceAll("[\\s\\-.()]", "");
        if (compact.startsWith("00")) {
            compact = "+" + compact.substring(2);
        }
        if (compact.startsWith("+")) {
            return new PhoneNumber(compact);
        }
        if (compact.length() == 11 && compact.startsWith("0")) {
            return new PhoneNumber(INDIA + compact.substring(1));
        }
        if (compact.length() == 12 && compact.startsWith("91")) {
            return new PhoneNumber("+" + compact);
        }
        return new PhoneNumber(INDIA + compact);
    }

    /** The last four digits, stored in clear for display ({@code ••••••3210}). */
    public String last4() {
        return e164.substring(e164.length() - 4);
    }

    @Override
    public String toString() {
        return "PhoneNumber[" + Masking.phone(e164) + "]";
    }
}
