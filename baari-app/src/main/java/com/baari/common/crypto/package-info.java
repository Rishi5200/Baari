/**
 * Protecting patient phone numbers (PLAN §9.4): normalising to E.164, encrypting at rest,
 * hashing for the one-active-token-per-phone check, and masking for display.
 *
 * <p>A named interface so other modules can use it; Spring Modulith would otherwise treat a
 * sub-package of {@code common} as internal.
 */
@NamedInterface("crypto")
package com.baari.common.crypto;

import org.springframework.modulith.NamedInterface;
