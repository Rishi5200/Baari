package com.baari.queue;

import java.time.Instant;
import java.util.UUID;

/**
 * One finished consultation: how long it took. Anonymous (no token id, name or phone), so it can be
 * used for statistics and model training.
 *
 * @param doctorId the doctor who saw the patient; null if unknown
 * @param startedAt when the consultation started
 */
public record ServiceSample(UUID doctorId, VisitType visitType, Instant startedAt, int serviceSeconds) {}
