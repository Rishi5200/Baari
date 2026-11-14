package com.baari.common;

/** Regexes shared by request records. Constants so they can be used in annotations. */
public final class ValidationPatterns {

    private ValidationPatterns() {}

    /** Lowercase words joined by single hyphens: "sharma-clinic", "dr-sharma". Goes into URLs. */
    public static final String SLUG = "^[a-z0-9]+(-[a-z0-9]+)*$";

    /** Lowercase login names; 3–40 chars of letters, digits, dot, underscore, hyphen. */
    public static final String USERNAME = "^[a-z0-9._-]{3,40}$";
}
