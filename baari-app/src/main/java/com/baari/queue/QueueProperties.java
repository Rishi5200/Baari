package com.baari.queue;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Queue settings (PLAN §14, {@code baari.queue}). Global for now; per-clinic overrides from the
 * {@code settings} jsonb columns come later.
 *
 * @param maxOpenTokens joins are refused once this many tokens of a day are still open (waiting,
 *     called, in consultation or no-show), so a flood of fake joins can't grow a queue forever
 * @param maxJoinsPerPhone QR joins a day one phone number may make in one queue (PLAN §9.3)
 */
@Validated
@ConfigurationProperties("baari.queue")
record QueueProperties(
        @DefaultValue("6") @Min(1) int defaultServiceMinutes,
        @DefaultValue("3") @Min(0) int noShowGraceMinutes,
        @DefaultValue("2") @Min(1) int recallInsertOffset,
        @DefaultValue("2") @Min(0) int maxConsecutivePriority,
        @DefaultValue("300") @Min(1) int maxOpenTokens,
        @DefaultValue("3") @Min(1) int maxJoinsPerPhone) {

    NextTokenPolicy.Settings policySettings() {
        return new NextTokenPolicy.Settings(maxConsecutivePriority, recallInsertOffset);
    }
}
