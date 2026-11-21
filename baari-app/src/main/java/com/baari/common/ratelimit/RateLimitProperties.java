package com.baari.common.ratelimit;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-IP rate limits (PLAN §9.3, {@code baari.rate-limit}). Tests turn them off, because every test
 * request comes from the same address; {@code RateLimitIT} turns them back on.
 */
@Validated
@ConfigurationProperties("baari.rate-limit")
record RateLimitProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10") @Min(1) int joinPerMinute,
        @DefaultValue("60") @Min(1) int tokenPagePerMinute,
        @DefaultValue("10") @Min(1) int loginPerMinute) {}
