package com.baari.clinic;

import java.util.List;
import java.util.Objects;

/**
 * What a patient may see about a queue before joining: names only, no secrets, no staff data.
 *
 * @param doctors the queue's active doctors, by name
 */
public record PublicQueue(QueueInfo info, String clinicName, List<DoctorView> doctors) {

    public PublicQueue {
        Objects.requireNonNull(info, "info");
        Objects.requireNonNull(clinicName, "clinicName");
        doctors = List.copyOf(doctors);
    }
}
