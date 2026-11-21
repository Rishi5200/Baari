package com.baari.queue;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Queue numbers for the Grafana dashboard (PLAN Phase 8): tokens issued (tokens per hour) and the
 * real waits from joining to the start of the consultation (p50 and p90 from a histogram). Recorded
 * after the transaction commits, so a rolled-back join or start is never counted.
 */
@Component
class QueueMetrics {

    private final MeterRegistry meters;
    private final DistributionSummary waits;

    QueueMetrics(MeterRegistry meters) {
        this.meters = meters;
        this.waits = DistributionSummary.builder("baari.wait.actual.seconds")
                .description("Time from joining to the start of the consultation")
                .baseUnit("seconds")
                .publishPercentileHistogram()
                .register(meters);
    }

    void tokenIssued(TokenSource source) {
        afterCommit(() -> Counter.builder("baari.tokens.issued")
                .description("Tokens issued, by how the patient joined")
                .tag("source", source.name())
                .register(meters)
                .increment());
    }

    void consultationStarted(long waitSeconds) {
        afterCommit(() -> waits.record(Math.max(0, waitSeconds)));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }
}
