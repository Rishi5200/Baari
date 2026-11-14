package com.baari.queue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntFunction;

/** Runs calls truly at the same time, for the PLAN §6.4 concurrency tests. */
final class Race {

    private Race() {}

    /** Result of one call: a value, or the exception it threw. */
    record Outcome<T>(T value, Throwable error) {

        boolean succeeded() {
            return error == null;
        }
    }

    /**
     * Starts {@code n} calls on virtual threads, releases them together through a start gate, and
     * waits for all of them.
     */
    static <T> List<Outcome<T>> run(int n, IntFunction<Callable<T>> call) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>(n);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < n; i++) {
                Callable<T> task = call.apply(i);
                futures.add(executor.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
        }
        List<Outcome<T>> outcomes = new ArrayList<>(n);
        for (Future<T> future : futures) {
            try {
                outcomes.add(new Outcome<>(future.get(), null));
            } catch (ExecutionException e) {
                outcomes.add(new Outcome<>(null, e.getCause()));
            }
        }
        return outcomes;
    }
}
