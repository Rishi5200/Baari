package com.baari.eta;

import com.baari.queue.VisitType;
import java.util.Objects;
import java.util.UUID;

/**
 * What an estimator may look at to guess one consultation's length.
 *
 * @param doctorId the doctor expected to see the patient; null if not known
 * @param hourOfDay the hour (0 to 23, clinic time) the consultation is expected to start
 */
public record ServiceContext(UUID doctorId, VisitType visitType, int hourOfDay) {

    public ServiceContext {
        Objects.requireNonNull(visitType, "visitType");
        if (hourOfDay < 0 || hourOfDay > 23) {
            throw new IllegalArgumentException("hourOfDay must be 0..23");
        }
    }
}
