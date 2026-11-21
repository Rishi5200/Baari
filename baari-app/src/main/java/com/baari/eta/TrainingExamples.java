package com.baari.eta;

import com.baari.queue.HistoricalToken;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Turns past tokens into (features, consultation length) pairs for the ML model (PLAN §8.6). Each
 * consultation is described exactly as the projector would have described it at the moment it
 * started: the doctor's session so far, the hour, and how many patients were still waiting. Nothing
 * that happened after the start is used (the consultation's own end is only the target), which
 * {@code TrainingExamplesTest} checks.
 *
 * <p>Pure.
 */
public final class TrainingExamples {

    /** One consultation to learn from. */
    public record Example(ServiceContext context, int serviceSeconds, Instant startedAt, LocalDate serviceDate) {}

    private TrainingExamples() {}

    public static List<Example> from(List<HistoricalToken> tokens, ZoneId zone) {
        Map<UUID, List<HistoricalToken>> byQueueDay =
                tokens.stream().collect(Collectors.groupingBy(HistoricalToken::queueDayId));
        // sessions are per doctor and day, across queues: a doctor serving two queues is one person
        Map<List<Object>, List<HistoricalToken>> consultsByDoctorDay = tokens.stream()
                .filter(t -> t.consultStartedAt() != null && t.doctorId() != null)
                .collect(Collectors.groupingBy(t -> List.of(t.doctorId(), t.serviceDate())));

        List<Example> examples = new ArrayList<>();
        for (List<HistoricalToken> consults : consultsByDoctorDay.values()) {
            List<HistoricalToken> inOrder = consults.stream()
                    .sorted(Comparator.comparing(HistoricalToken::consultStartedAt)
                            .thenComparing(HistoricalToken::id))
                    .toList();
            Sessions.SoFar session = Sessions.SoFar.NONE;
            for (HistoricalToken c : inOrder) {
                Instant start = c.consultStartedAt();
                session = session.next(start);
                if (!c.hasServiceTime()) {
                    continue; // still counts towards the session, but has no length to learn
                }
                int seconds = (int) (c.completedAt().getEpochSecond() - start.getEpochSecond());
                if (seconds < RollingStatsEstimator.MIN_SERVICE_SECONDS
                        || seconds > RollingStatsEstimator.MAX_SERVICE_SECONDS) {
                    continue;
                }
                int waiting = (int) byQueueDay.get(c.queueDayId()).stream()
                        .filter(t -> t.waitingAt(start))
                        .count();
                ZonedDateTime local = start.atZone(zone);
                ServiceContext context = new ServiceContext(
                        c.doctorId(),
                        c.visitType(),
                        local.getHour(),
                        c.priority(),
                        local.getDayOfWeek(),
                        session.started(),
                        session.minutesInto(start),
                        waiting);
                examples.add(new Example(context, seconds, start, c.serviceDate()));
            }
        }
        examples.sort(Comparator.comparing(Example::startedAt));
        return List.copyOf(examples);
    }

    /** Finished consultations from days before {@code day}, newest first, as Rolling expects. */
    public static List<RollingStatsEstimator.Sample> rollingSamples(
            List<HistoricalToken> tokens, LocalDate day, ZoneId zone) {
        return tokens.stream()
                .filter(t -> t.serviceDate().isBefore(day) && t.hasServiceTime() && t.doctorId() != null)
                .sorted(Comparator.comparing(HistoricalToken::consultStartedAt).reversed())
                .map(t -> new RollingStatsEstimator.Sample(
                        t.doctorId(),
                        t.visitType(),
                        t.consultStartedAt().atZone(zone).getHour(),
                        (int) (t.completedAt().getEpochSecond()
                                - t.consultStartedAt().getEpochSecond())))
                .limit(5000)
                .toList();
    }
}
