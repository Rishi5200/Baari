package com.baari.eta;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * ML model settings (PLAN §8.6, {@code baari.ml}).
 *
 * @param minTrainingSamples finished consultations in the training window a clinic needs first
 * @param trainHour clinic-local hour of the nightly run (at minute 30)
 */
@Validated
@ConfigurationProperties("baari.ml")
record MlProperties(
        @DefaultValue("300") @Min(1) int minTrainingSamples,
        @DefaultValue("2") @Min(0) @Max(23) int trainHour) {}
