package com.baari.eta;

import java.time.Instant;

/**
 * The model's raw prediction for one waiting patient, for staff: when the consultation is expected
 * to start. Not smoothed or rounded; patients see {@link EtaDisplay} instead.
 */
public record TokenEstimate(int position, Instant expectedStartAt, int waitSeconds) {}
