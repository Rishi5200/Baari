package com.baari.eta;

import com.baari.queue.HistoricalToken;
import com.baari.queue.NextTokenPolicy;
import com.baari.queue.Priority;
import com.baari.queue.TokenStatus;
import com.baari.queue.VisitType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Replays simulated clinic days through the real projector and compares every way of predicting with
 * what happened (PLAN Phase 6 and 7). A plain command line program: no Spring, no database, no clock,
 * so a number in the report can be reproduced from the CSV.
 *
 * <pre>java -cp baari-app/target/classes:&lt;dependencies&gt; com.baari.eta.Backtest --tokens build/sim/tokens.csv
 *     --out docs/eta-evaluation.md [--test-days 14] [--default-minutes 6]</pre>
 *
 * <p>The newest {@code test-days} days are scored. Rolling on a day only sees consultations from
 * earlier days; the ML candidates are trained once on every day before the test window. Days are
 * replayed by {@link WaitReplay}, the same code the nightly training job uses.
 */
public final class Backtest {

    static final String DEFAULT = DefaultEstimator.NAME;
    static final String ROLLING = RollingStatsEstimator.NAME;
    /** Rolling (or Default while history is short, as production does) with the nightly range calibration. */
    static final String CALIBRATED = "ROLLING+CALIBRATION";

    static final String ML_LINEAR = MlEstimator.PREFIX + ModelTrainer.LINEAR;
    static final String ML_TREE = MlEstimator.PREFIX + ModelTrainer.TREE;
    static final List<String> VARIANTS = List.of(DEFAULT, ROLLING, CALIBRATED, ML_LINEAR, ML_TREE);

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
            return uuid(date + "/" + doctor + "/" + number);
        }

        boolean completed() {
            return "COMPLETED".equals(status);
        }

        /** In the simulator every doctor has their own line, so a queue day is (date, doctor). */
        HistoricalToken toHistorical() {
            return new HistoricalToken(
                    id(),
                    uuid("day/" + date + "/" + doctor),
                    date,
                    doctorId(doctor),
                    number,
                    visit,
                    priority,
                    TokenStatus.valueOf(status),
                    joinedAt,
                    calledAt,
                    startedAt,
                    completedAt,
                    null,
                    false);
        }
    }

    record Settings(int testDays, int defaultServiceSeconds, int minSamples, int transitionGapSeconds, ZoneId zone) {}

    /**
     * @param serviceMetrics consultation-length error on the test days, by estimator
     * @param calibrationAtTestStart the calibrated variant's factor on the first scored day
     * @param calibrationAtEnd its factor after the last day
     */
    record Result(
            List<LocalDate> trainDays,
            List<LocalDate> testDays,
            List<WaitReplay.Observation> observations,
            Map<String, ModelTrainer.ServiceMetrics> serviceMetrics,
            double calibrationAtTestStart,
            double calibrationAtEnd) {}

    /** Tribuo logs every 1000 training steps through java.util.logging; kept in a field so it is not collected. */
    private static final java.util.logging.Logger TRIBUO_LOG = java.util.logging.Logger.getLogger("org.tribuo");

    private Backtest() {}

    public static void main(String[] args) throws IOException {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            opts.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        TRIBUO_LOG.setLevel(java.util.logging.Level.WARNING);
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

    /**
     * Scores the newest {@code testDays} days. Every earlier day is replayed too, but only for the
     * calibrated variant: it plays the nightly calibration job (PLAN 8.5), which after each day looks
     * at the coverage of the last {@value RangeCalibration#WINDOW_DAYS} days and moves the factor one
     * step.
     */
    static Result run(List<Row> rows, Settings settings) {
        TreeSet<LocalDate> allDays = rows.stream().map(Row::date).collect(Collectors.toCollection(TreeSet::new));
        if (allDays.size() <= settings.testDays()) {
            throw new IllegalArgumentException("need more days than --test-days (" + allDays.size() + " found)");
        }
        List<LocalDate> days = new ArrayList<>(allDays);
        List<LocalDate> test = days.subList(days.size() - settings.testDays(), days.size());
        List<LocalDate> train = days.subList(0, days.size() - settings.testDays());
        LocalDate testStart = test.get(0);
        List<HistoricalToken> history = rows.stream().map(Row::toHistorical).toList();
        Map<LocalDate, List<HistoricalToken>> byDay =
                history.stream().collect(Collectors.groupingBy(HistoricalToken::serviceDate));
        WaitReplay.Settings replay = new WaitReplay.Settings(settings.zone(), settings.transitionGapSeconds(), POLICY);
        ServiceTimeEstimator fallback = new DefaultEstimator(settings.defaultServiceSeconds());

        // ML: trained once on everything before the test window, scored on the test window
        List<HistoricalToken> before = history.stream()
                .filter(t -> t.serviceDate().isBefore(testStart))
                .toList();
        List<HistoricalToken> after = history.stream()
                .filter(t -> !t.serviceDate().isBefore(testStart))
                .toList();
        List<TrainingExamples.Example> trainExamples = TrainingExamples.from(before, settings.zone());
        List<TrainingExamples.Example> testExamples = TrainingExamples.from(after, settings.zone());
        List<ModelTrainer.Candidate> candidates = ModelTrainer.train(trainExamples, testExamples);
        RollingStatsEstimator rollingAtTestStart = new RollingStatsEstimator(
                samplesBefore(history, testStart, settings.zone()), settings.minSamples(), fallback);
        Map<String, ModelTrainer.ServiceMetrics> serviceMetrics = new LinkedHashMap<>();
        serviceMetrics.put(DEFAULT, ModelTrainer.score(fallback, testExamples));
        serviceMetrics.put(ROLLING, ModelTrainer.score(rollingAtTestStart, testExamples));
        for (ModelTrainer.Candidate c : candidates) {
            serviceMetrics.put(MlEstimator.PREFIX + c.model().algorithm(), c.test());
        }

        List<WaitReplay.Observation> scored = new ArrayList<>();
        ArrayDeque<List<WaitReplay.Observation>> window = new ArrayDeque<>();
        double factor = RangeCalibration.START;
        double factorAtTestStart = factor;
        for (LocalDate day : days) {
            boolean isTest = !day.isBefore(testStart);
            if (day.equals(testStart)) {
                factorAtTestStart = factor;
            }
            RollingStatsEstimator rolling = new RollingStatsEstimator(
                    samplesBefore(history, day, settings.zone()), settings.minSamples(), fallback);
            ServiceTimeEstimator inUse = rolling.hasEnoughData() ? rolling : fallback;
            List<WaitReplay.Variant> variants = new ArrayList<>();
            if (isTest) {
                variants.add(new WaitReplay.Variant(DEFAULT, fallback, 1.0));
                variants.add(new WaitReplay.Variant(ROLLING, rolling, 1.0));
            }
            variants.add(new WaitReplay.Variant(CALIBRATED, inUse, factor));
            if (isTest) {
                for (ModelTrainer.Candidate c : candidates) {
                    variants.add(new WaitReplay.Variant(
                            MlEstimator.PREFIX + c.model().algorithm(), new MlEstimator(c.model(), 1, rolling), 1.0));
                }
            }
            List<WaitReplay.Observation> dayObs = WaitReplay.replay(byDay.get(day), variants, replay);
            if (isTest) {
                scored.addAll(dayObs);
            }
            window.addLast(
                    dayObs.stream().filter(o -> o.variant().equals(CALIBRATED)).toList());
            if (window.size() > RangeCalibration.WINDOW_DAYS) {
                window.removeFirst();
            }
            List<WaitReplay.Observation> recent =
                    window.stream().flatMap(List::stream).toList();
            long covered =
                    recent.stream().filter(WaitReplay.Observation::covered).count();
            factor = RangeCalibration.adjust(factor, covered, recent.size());
        }
        return new Result(train, test, scored, serviceMetrics, factorAtTestStart, factor);
    }

    /** Finished consultations from days before {@code day}, newest first, as Rolling expects. */
    static List<RollingStatsEstimator.Sample> samplesBefore(List<HistoricalToken> tokens, LocalDate day, ZoneId zone) {
        return tokens.stream()
                .filter(t -> t.serviceDate().isBefore(day) && t.hasServiceTime())
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

    private static UUID doctorId(String name) {
        return uuid("doctor/" + name);
    }

    private static UUID uuid(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
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

    static String report(Result result, Settings settings, String source) {
        StringBuilder md = new StringBuilder();
        LocalDate first = result.trainDays().get(0);
        LocalDate testFirst = result.testDays().get(0);
        LocalDate last = result.testDays().get(result.testDays().size() - 1);
        md.append("# ETA evaluation\n\n");
        md.append("Generated by `com.baari.eta.Backtest` from `").append(source).append("`. ");
        md.append("Do not edit by hand; rerun the backtest.\n\n");
        md.append("## Setup\n\n");
        md.append(String.format(
                Locale.ROOT,
                "- Data: simulated clinic days (`baari-simulator`, `sim/demo-clinic.json`), %d days: %s to %s.%n",
                result.trainDays().size() + result.testDays().size(),
                first,
                last));
        md.append(String.format(
                Locale.ROOT,
                "- Scored: the newest %d days (%s to %s). Rolling on a day only sees consultations from earlier days; "
                        + "the ML models are trained once on the %d days before %s (time-based, never a random split).%n",
                result.testDays().size(),
                testFirst,
                last,
                result.trainDays().size(),
                testFirst));
        md.append(
                "- Predictions are logged at the production triggers: JOIN and the first time a patient is within 10, 5, 3 and 1 places. Patients who never reached a consultation (no-shows) are not scored.\n");
        md.append(String.format(
                Locale.ROOT,
                "- The wait is the raw projector output (no display smoothing). Default assumes %d min per consultation; "
                        + "all variants use a %d s gap between patients and no no-show allowance.%n",
                settings.defaultServiceSeconds() / 60,
                settings.transitionGapSeconds()));
        md.append(String.format(
                Locale.ROOT,
                "- %s: Rolling once the clinic has enough history, with the range widened by the nightly calibration "
                        + "factor (PLAN 8.5: coverage of the last %d days, one step of %.1f per night toward 60 to 70%%, "
                        + "within %.1f to %.1f). The factor was %.1f on the first scored day and %.1f after the last.%n",
                CALIBRATED,
                RangeCalibration.WINDOW_DAYS,
                RangeCalibration.STEP,
                RangeCalibration.MIN,
                RangeCalibration.MAX,
                result.calibrationAtTestStart(),
                result.calibrationAtEnd()));
        md.append(String.format(
                Locale.ROOT,
                "- %s and %s: Tribuo models of the log of the consultation length (PLAN 8.6) from doctor, visit type, "
                        + "priority, hour, weekday, place in the doctor's session, time into the session and queue "
                        + "length; ranges from the spread of their training errors, no calibration.%n",
                ML_LINEAR,
                ML_TREE));
        md.append(
                "- Error = predicted minus actual, in minutes. Bias above 0 means the estimate was too long. Coverage = share of actual waits inside the shown range (the target is 60 to 70%).\n\n");

        md.append("## Consultation length on the test days\n\n");
        md.append("| Estimator | n | MAE (min) | median AE | p90 AE |\n|---|---:|---:|---:|---:|\n");
        result.serviceMetrics()
                .forEach((name, m) -> md.append(String.format(
                        Locale.ROOT,
                        "| %s | %d | %.2f | %.2f | %.2f |%n",
                        name,
                        m.n(),
                        m.maeS() / 60,
                        m.medianAeS() / 60,
                        m.p90AeS() / 60)));

        Map<String, List<WaitReplay.Observation>> byVariant = result.observations().stream()
                .collect(Collectors.groupingBy(WaitReplay.Observation::variant, TreeMap::new, Collectors.toList()));
        md.append("\n## Wait time, overall\n\n");
        header(md, "Variant");
        for (String variant : VARIANTS) {
            row(md, variant, WaitReplay.stats(byVariant.getOrDefault(variant, List.of())));
        }
        md.append("\n## Wait time, by trigger\n\n");
        header(md, "Trigger / variant");
        for (String trigger : WaitReplay.TRIGGERS) {
            for (String variant : VARIANTS) {
                List<WaitReplay.Observation> subset = byVariant.getOrDefault(variant, List.of()).stream()
                        .filter(o -> o.trigger().equals(trigger))
                        .toList();
                row(md, trigger + " / " + variant, WaitReplay.stats(subset));
            }
        }

        WaitReplay.Stats d = WaitReplay.stats(byVariant.getOrDefault(DEFAULT, List.of()));
        WaitReplay.Stats r = WaitReplay.stats(byVariant.getOrDefault(ROLLING, List.of()));
        WaitReplay.Stats c = WaitReplay.stats(byVariant.getOrDefault(CALIBRATED, List.of()));
        String bestMl = VARIANTS.stream()
                .filter(v -> v.startsWith(MlEstimator.PREFIX))
                .min(Comparator.comparingDouble(v ->
                        WaitReplay.stats(byVariant.getOrDefault(v, List.of())).maeMin()))
                .orElseThrow();
        WaitReplay.Stats m = WaitReplay.stats(byVariant.getOrDefault(bestMl, List.of()));
        md.append("\n## Reading\n\n");
        md.append(String.format(
                Locale.ROOT,
                "Rolling's mean wait error is %.1f min against %.1f min for Default (%.0f%% %s). ",
                r.maeMin(),
                d.maeMin(),
                Math.abs(100 * (d.maeMin() - r.maeMin()) / d.maeMin()),
                r.maeMin() <= d.maeMin() ? "lower" : "higher"));
        md.append(String.format(
                Locale.ROOT,
                "The best ML model, %s, is at %.1f min, %.0f%% %s than Rolling%s. ",
                bestMl,
                m.maeMin(),
                Math.abs(100 * (r.maeMin() - m.maeMin()) / r.maeMin()),
                m.maeMin() <= r.maeMin() ? "lower" : "higher",
                m.maeMin() <= 0.95 * r.maeMin()
                        ? ", enough to be promoted under the 5% rule (PLAN 8.6)"
                        : ", not enough to be promoted under the 5% rule (PLAN 8.6)"));
        md.append(String.format(
                Locale.ROOT,
                "Without calibration the shown range holds the actual wait %.0f%% of the time (Rolling); "
                        + "calibration brings it to %.0f%% without moving the estimate.%n",
                100 * r.coverage(),
                100 * c.coverage()));
        md.append(
                "\nAll variants lean short (negative bias, worst at JOIN). Late session starts and patients who join "
                        + "before the doctor arrives are not modelled, because the app stores no session timetable (ADR 0012). "
                        + "A better consultation-length model cannot fix that part of the error, which is why its gain on the "
                        + "wait is smaller than its gain on the consultation length. On the consultation length itself the models "
                        + "are close to Rolling too: most of the remaining error is random spread that no feature explains (the "
                        + "simulator draws each length from a lognormal around the doctor's median), so there is little left to learn.\n");
        return md.toString();
    }

    private static void header(StringBuilder md, String first) {
        md.append("| ").append(first).append(" | n | MAE (min) | median AE | p90 AE | bias | range coverage |\n");
        md.append("|---|---:|---:|---:|---:|---:|---:|\n");
    }

    private static void row(StringBuilder md, String label, WaitReplay.Stats s) {
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
