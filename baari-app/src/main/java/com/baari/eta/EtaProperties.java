package com.baari.eta;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ETA settings (PLAN §14, {@code baari.eta}).
 *
 * @param defaultServiceMinutes consultation length used until the clinic has enough history
 * @param minSamples finished consultations a group needs before its statistics are trusted
 * @param transitionGapSeconds time between one consultation ending and the next starting
 * @param noShowRate share of patients assumed not to turn up (0 until it is learned, PLAN §8.3)
 * @param historyDays how far back finished consultations count
 * @param historyLimit most consultations loaded per clinic
 * @param projectionCacheSeconds how long a projection is reused while the queue has not changed
 */
@Validated
@ConfigurationProperties("baari.eta")
record EtaProperties(
        @DefaultValue("6") @Min(1) int defaultServiceMinutes,
        @DefaultValue("20") @Min(1) int minSamples,
        @DefaultValue("30") @Min(0) int transitionGapSeconds,

        @DefaultValue("0") @DecimalMin("0") @DecimalMax("0.99")
        double noShowRate,

        @DefaultValue("90") @Min(1) int historyDays,
        @DefaultValue("5000") @Min(1) int historyLimit,
        @DefaultValue("10") @Min(0) int projectionCacheSeconds) {}
