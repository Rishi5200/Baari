package com.baari.eta;

/** Guesses how long a consultation will take (PLAN §8.2). Implementations are pure. */
public interface ServiceTimeEstimator {

    Distribution estimate(ServiceContext context);

    /** Stored with every logged prediction: {@code DEFAULT}, {@code ROLLING}, later {@code ML:<version>}. */
    String name();
}
