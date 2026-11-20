package com.baari.eta;

import com.baari.queue.NextTokenPolicy;
import com.baari.queue.Priority;
import com.baari.queue.QueueSnapshot;
import com.baari.queue.TokenStatus;
import com.baari.queue.TokenView;
import com.baari.queue.VisitType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Replays simulated clinic days through the real projector and compares every estimator's
 * prediction with what happened (PLAN Phase 6). It is a plain command line program: no Spring, no
 * database, no clock. It only uses pure classes, so a number in the report can be reproduced from the CSV.
 *
 * <pre>java -cp baari-app/target/classes com.baari.eta.Backtest --tokens build/sim/tokens.csv
 *     --out docs/eta-evaluation.md [--test-days 14] [--default-minutes 6]</pre>
 *
 * <p>How a day is replayed: for each doctor, at every moment a patient joined or was called, the
 * queue is rebuilt as it was (who waits, who is in the room) and handed to {@link QueueProjector}.
 * Predictions are logged at the same triggers as production: JOIN, and the first time a patient is
 * within 10, 5, 3 and 1 places of the front. The Rolling estimator for a day only sees consultations
 * from earlier days, so nothing from the future leaks into it. Only the {@code test-days} newest
 * days are scored.
 */
public final class Backtest {

    static final String DEFAULT = DefaultEstimator.NAME;
    static final String ROLLING = RollingStatsEstimator.NAME;
    private static final int[] MILESTONES = {10, 5, 3, 1};
    private static final List<String> TRIGGERS =
            List.of("JOIN", "POSITION_10", "POSITION_5", "POSITION_3", "POSITION_1");
    /** A no-show keeps the doctor waiting this long, as in the simulator. */
    private static final long NO_SHOW_GRACE_S = 180;

    private static final NextTokenPolicy.Settings POLICY = new NextTokenPolicy.Settings(2, 2);

    /** One simulated patient as read from the CSV. Times are instants; {@code null} means "never". */
    record Row(
            LocalDate date,
            String doctor,
            int number,
            VisitType visit,
            Priority priority,
            String status,
            Instant joinedAt,
            Instant calledAt,
            Instant startedAt,
            Instant completedAt) {

        UUID id() {
            return UUID.nameUUIDFromBytes((date + "/" + doctor + "/" + number).getBytes(StandardCharsets.UTF_8));
        }

        boolean completed() {
            return "COMPLETED".equals(status);
        }
    }

    /** One scored prediction. */
    record Observation(String estimator, String trigger, int predictedS, int minS, int maxS, int actualS) {
        int errorS() {
            return predictedS - actualS;
        }

        boolean covered() {
            return actualS >= minS && actualS <= maxS;
        }
    }

    record Settings(int testDays, int defaultServiceSeconds, int minSamples, int transitionGapSeconds, ZoneId zone) {}

    private Backtest() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        Path tokens = Path.of(required(opts, "tokens"));
        Path out = Path.of(required(opts, "out"));
        Settings settings = new Settings(
                Integer.parseInt(opts.getOrDefault("test-days", "14")),
                Integer.parseInt(opts.getOrDefault("default-minutes", "6")) * 60,
                20,
                30,
                ZoneId.of(opts.getOrDefault("zone", "Asia/Kolkata")));
        List<Row> rows = readRows(Files.readAllLines(tokens, StandardCharsets.UTF_8));
        Result result = run(rows, settings);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report(result, settings, tokens.getFileName().toString()), StandardCharsets.UTF_8);
        System.out.println("scored " + result.observations().size() + " predictions on "
                + result.testDays().size() + " test days; report written to " + out);
    }

    record Result(List<LocalDate> trainDays, List<LocalDate> testDays, List<Observation> observations) {}

    /** Replays the newest {@code testDays} days; the days before them only feed the Rolling estimator. */
    static Result run(List<Row> rows, Settings settings) {
        TreeSet<LocalDate> allDays = rows.stream().map(Row::date).collect(Collectors.toCollection(TreeSet::new));
        if (allDays.size() <= settings.testDays()) {
            throw new IllegalArgumentException("need more days than --test-days (" + allDays.size() + " found)");
        }
        List<LocalDate> days = new ArrayList<>(allDays);
        List<LocalDate> test = days.subList(days.size() - settings.testDays(), days.size());
        List<LocalDate> train = days.subList(0, days.size() - settings.testDays());
        Map<LocalDate, List<Row>> byDay = rows.stream().collect(Collectors.groupingBy(Row::date));

        List<Observation> all = new ArrayList<>();
        for (LocalDate day : test) {
            List<RollingStatsEstimator.Sample> history = samplesBefore(rows, day, settings.zone());
            ServiceTimeEstimator fallback = new DefaultEstimator(settings.defaultServiceSeconds());
            Map<String, ServiceTimeEstimator> estimators = new LinkedHashMap<>();
            estimators.put(DEFAULT, fallback);
            estimators.put(ROLLING, new RollingStatsEstimator(history, settings.minSamples(), fallback));
            Map<String, List<Row>> byDoctor = byDay.get(day).stream()
                    .collect(Collectors.groupingBy(Row::doctor, TreeMap::new, Collectors.toList()));
            for (Map.Entry<String, List<Row>> doctor : byDoctor.entrySet()) {
                all.addAll(replay(doctor.getKey(), doctor.getValue(), estimators, settings));
            }
        }
        return new Result(train, test, all);
    }

    /** Finished consultations from days before {@code day}, newest first, as Rolling expects. */
    private static List<RollingStatsEstimator.Sample> samplesBefore(List<Row> rows, LocalDate day, ZoneId zone) {
        return rows.stream()
                .filter(r -> r.date().isBefore(day) && r.completed())
                .sorted(Comparator.comparing(Row::startedAt).reversed())
                .map(r -> new RollingStatsEstimator.Sample(
                        doctorId(r.doctor()),
                        r.visit(),
                        r.startedAt().atZone(zone).getHour(),
                        (int) (r.completedAt().getEpochSecond() - r.startedAt().getEpochSecond())))
                .limit(5000)
                .toList();
    }

    private static UUID doctorId(String name) {
        return UUID.nameUUIDFromBytes(("doctor/" + name).getBytes(StandardCharsets.UTF_8));
    }

    /** Replays one doctor's day, one projection per estimator at every join or call. */
    private static List<Observation> replay(
            String doctor, List<Row> rows, Map<String, ServiceTimeEstimator> estimators, Settings settings) {
        UUID doctorId = doctorId(doctor);
        List<Row> byCall =
                rows.stream().sorted(Comparator.comparing(Row::calledAt)).toList();
        TreeSet<Instant> moments = new TreeSet<>();
        rows.forEach(r -> {
            moments.add(r.joinedAt());
            moments.add(r.calledAt());
        });
        Map<UUID, Row> byId = rows.stream().collect(Collectors.toMap(Row::id, Function.identity()));
        Map<UUID, Integer> joinPosition = new HashMap<>();
        Map<UUID, Set<String>> logged = new HashMap<>();
        QueueProjector.Params params = new QueueProjector.Params(settings.transitionGapSeconds(), 0.0, 1.0);

        List<Observation> out = new ArrayList<>();
        for (Instant now : moments) {
            List<Row> waiting = rows.stream()
                    .filter(r -> !r.joinedAt().isAfter(now) && (r.calledAt().isAfter(now) || calledOnArrival(r, now)))
                    .toList();
            if (waiting.isEmpty()) {
                continue;
            }
            QueueProjector.Consult current = rows.stream()
                    .filter(r -> r.completed()
                            && !r.startedAt().isAfter(now)
                            && r.completedAt().isAfter(now))
                    .findFirst()
                    .map(r -> new QueueProjector.Consult(r.id(), r.visit(), r.startedAt()))
                    .orElse(null);
            List<QueueProjector.Called> called = rows.stream()
                    .filter(r -> !r.calledAt().isAfter(now) && !calledOnArrival(r, now) && onItsWay(r, now))
                    .map(r -> new QueueProjector.Called(r.id(), r.visit(), r.calledAt(), doctorId))
                    .toList();
            List<TokenView> views = waiting.stream()
                    .map(r -> new TokenView(
                            r.id(),
                            r.number(),
                            r.priority(),
                            TokenStatus.WAITING,
                            TokenView.initialSortKey(r.number()),
                            r.joinedAt()))
                    .toList();
            Map<UUID, VisitType> visits = waiting.stream().collect(Collectors.toMap(Row::id, Row::visit));
            QueueProjector.Input input = new QueueProjector.Input(
                    now,
                    settings.zone(),
                    List.of(new QueueProjector.Server(doctorId, current)),
                    called,
                    new QueueSnapshot(views),
                    visits,
                    policyStateAt(byCall, now),
                    POLICY);

            Map<String, Map<UUID, QueueProjector.Projection>> projections = new LinkedHashMap<>();
            for (Map.Entry<String, ServiceTimeEstimator> e : estimators.entrySet()) {
                Map<UUID, QueueProjector.Projection> byToken = new HashMap<>();
                QueueProjector.project(input, e.getValue(), params).forEach(p -> byToken.put(p.tokenId(), p));
                projections.put(e.getKey(), byToken);
            }

            // positions do not depend on the estimator, so any estimator's projection says who crossed what
            for (QueueProjector.Projection p : projections.get(DEFAULT).values()) {
                Row row = byId.get(p.tokenId());
                Set<String> done = logged.computeIfAbsent(p.tokenId(), id -> new HashSet<>());
                List<String> fired = new ArrayList<>();
                if (!done.contains("JOIN")) {
                    joinPosition.put(p.tokenId(), p.position());
                    fired.add("JOIN");
                }
                for (int milestone : MILESTONES) {
                    String trigger = "POSITION_" + milestone;
                    if (p.position() <= milestone
                            && joinPosition.get(p.tokenId()) > milestone
                            && !done.contains(trigger)) {
                        fired.add(trigger);
                    }
                }
                for (String trigger : fired) {
                    done.add(trigger);
                    if (!row.completed()) {
                        continue; // no consultation happened, so there is no actual wait to compare with
                    }
                    int actual = (int) (row.startedAt().getEpochSecond() - now.getEpochSecond());
                    for (Map.Entry<String, Map<UUID, QueueProjector.Projection>> e : projections.entrySet()) {
                        QueueProjector.Projection mine = e.getValue().get(p.tokenId());
                        out.add(new Observation(
                                e.getKey(),
                                trigger,
                                mine.waitSeconds(),
                                mine.rangeMinSeconds(),
                                mine.rangeMaxSeconds(),
                                actual));
                    }
                }
            }
        }
        return out;
    }

    /**
     * A patient who walks in to an idle doctor is called at the very second they join. Production
     * still logs their JOIN prediction (they exist as WAITING for that instant), so the replay does too.
     */
    private static boolean calledOnArrival(Row r, Instant now) {
        return r.calledAt().equals(r.joinedAt()) && r.joinedAt().equals(now);
    }

    /** Called, and either not yet in the room or (for a no-show) still within the doctor's wait. */
    private static boolean onItsWay(Row r, Instant now) {
        if (r.completed()) {
            return r.startedAt().isAfter(now);
        }
        return now.isBefore(r.calledAt().plusSeconds(NO_SHOW_GRACE_S));
    }

    /** The policy's memory after every call up to {@code now}: same update rules as {@link NextTokenPolicy}. */
    private static NextTokenPolicy.State policyStateAt(List<Row> byCall, Instant now) {
        int counter = 0;
        for (Row r : byCall) {
            if (r.calledAt().isAfter(now)) {
                break;
            }
            if (r.priority() == Priority.PRIORITY) {
                counter++;
            } else if (r.priority() == Priority.NORMAL) {
                counter = 0;
            }
        }
        return new NextTokenPolicy.State(counter);
    }

    // ---- reading and reporting -------------------------------------------------------------

    static List<Row> readRows(List<String> lines) {
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) {
                continue;
            }
            String[] c = lines.get(i).split(",", -1);
            rows.add(new Row(
                    LocalDate.parse(c[0]),
                    c[1],
                    Integer.parseInt(c[2]),
                    VisitType.valueOf(c[3]),
                    Priority.valueOf(c[4]),
                    c[6],
                    OffsetDateTime.parse(c[7]).toInstant(),
                    OffsetDateTime.parse(c[8]).toInstant(),
                    c[9].isEmpty() ? null : OffsetDateTime.parse(c[9]).toInstant(),
                    c[10].isEmpty() ? null : OffsetDateTime.parse(c[10]).toInstant()));
        }
        return rows;
    }

    /** Summary numbers of a set of observations, in minutes. */
    record Stats(int n, double maeMin, double medianMin, double p90Min, double biasMin, double coverage) {}

    static Stats stats(List<Observation> obs) {
        if (obs.isEmpty()) {
            return new Stats(0, Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN);
        }
        List<Double> abs =
                obs.stream().map(o -> Math.abs(o.errorS()) / 60.0).sorted().toList();
        double mae = abs.stream().mapToDouble(Double::doubleValue).average().orElseThrow();
        double bias = obs.stream().mapToDouble(o -> o.errorS() / 60.0).average().orElseThrow();
        double covered = obs.stream().filter(Observation::covered).count() / (double) obs.size();
        return new Stats(
                obs.size(),
                mae,
                RollingStatsEstimator.quantile(abs, 0.5),
                RollingStatsEstimator.quantile(abs, 0.9),
                bias,
                covered);
    }

    static String report(Result result, Settings settings, String source) {
        StringBuilder md = new StringBuilder();
        md.append("# ETA evaluation: Default vs Rolling\n\n");
        md.append("Generated by `com.baari.eta.Backtest` from `").append(source).append("`. ");
        md.append("Do not edit by hand; rerun the backtest.\n\n");
        md.append("## Setup\n\n");
        md.append("- Data: simulated clinic days (`baari-simulator`, `sim/demo-clinic.json`), ")
                .append(result.trainDays().size() + result.testDays().size())
                .append(" days: ")
                .append(result.trainDays().get(0))
                .append(" to ")
                .append(result.testDays().get(result.testDays().size() - 1))
                .append(".\n");
        md.append("- Scored: the newest ")
                .append(result.testDays().size())
                .append(" days (")
                .append(result.testDays().get(0))
                .append(" to ")
                .append(result.testDays().get(result.testDays().size() - 1))
                .append(
                        "). Rolling on a day only sees consultations from earlier days (time-based, never a random split).\n");
        md.append(
                "- Predictions are logged at the production triggers: JOIN and the first time a patient is within 10, 5, 3 and 1 places. Patients who never reached a consultation (no-shows) are not scored.\n");
        md.append("- The wait is the raw projector output (no display smoothing). Default assumes ")
                .append(settings.defaultServiceSeconds() / 60)
                .append(" min per consultation; both use a ")
                .append(settings.transitionGapSeconds())
                .append(" s gap between patients and no no-show allowance, as production does today.\n");
        md.append(
                "- Error = predicted minus actual wait, in minutes. Bias above 0 means the estimate was too long. Coverage = share of actual waits inside the shown p20 to p80 range (the range is built to cover about 60%).\n\n");

        Map<String, List<Observation>> byEstimator = result.observations().stream()
                .collect(Collectors.groupingBy(Observation::estimator, TreeMap::new, Collectors.toList()));
        md.append("## Overall\n\n");
        header(md, "Estimator");
        for (String estimator : List.of(DEFAULT, ROLLING)) {
            row(md, estimator, stats(byEstimator.getOrDefault(estimator, List.of())));
        }
        md.append("\n## By trigger\n\n");
        header(md, "Trigger / estimator");
        for (String trigger : TRIGGERS) {
            for (String estimator : List.of(DEFAULT, ROLLING)) {
                List<Observation> subset = byEstimator.getOrDefault(estimator, List.of()).stream()
                        .filter(o -> o.trigger().equals(trigger))
                        .toList();
                row(md, trigger + " / " + estimator, stats(subset));
            }
        }
        Stats d = stats(byEstimator.getOrDefault(DEFAULT, List.of()));
        Stats r = stats(byEstimator.getOrDefault(ROLLING, List.of()));
        md.append("\n## Reading\n\n");
        md.append(String.format(
                Locale.ROOT,
                "Rolling's mean error is %.1f min against %.1f min for Default (%.0f%% %s). ",
                r.maeMin(),
                d.maeMin(),
                Math.abs(100 * (d.maeMin() - r.maeMin()) / d.maeMin()),
                r.maeMin() <= d.maeMin() ? "lower" : "higher"));
        md.append(String.format(
                Locale.ROOT,
                "The shown range holds the actual wait %.0f%% of the time for Rolling and %.0f%% for Default.%n",
                100 * r.coverage(),
                100 * d.coverage()));
        md.append(
                        "\nThe range is meant to hold about 60% of actual waits, so both are too narrow, and both lean short ")
                .append("(negative bias, worst at JOIN). The projector does not yet model late session starts, ")
                .append(
                        "a doctor slowing down through a session or no-shows, and it has no calibration factor (PLAN 8.5). ")
                .append("These are the first things to fix before an ML model is compared with Rolling.\n");
        return md.toString();
    }

    private static void header(StringBuilder md, String first) {
        md.append("| ").append(first).append(" | n | MAE (min) | median AE | p90 AE | bias | range coverage |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
    }

    private static void row(StringBuilder md, String label, Stats s) {
        md.append(String.format(
                Locale.ROOT,
                "| %s | %d | %.1f | %.1f | %.1f | %+.1f | %.0f%% |%n",
                label,
                s.n(),
                s.maeMin(),
                s.medianMin(),
                s.p90Min(),
                s.biasMin(),
                100 * s.coverage()));
    }

    private static String required(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return v;
    }
}
