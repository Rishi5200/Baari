package com.baari.simulator;

/** One simulated patient. Times are seconds after midnight of the service date; -1 means "never". */
record SimToken(
        String doctor,
        int number,
        String visitType,
        String priority,
        String source,
        String status,
        long joinedAt,
        long calledAt,
        long consultStartedAt,
        long completedAt) {}
