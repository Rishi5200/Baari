package com.baari.eta;

import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.QueueDirectory;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.ForbiddenException;
import com.baari.queue.HistoricalToken;
import com.baari.queue.QueueQueryService;
import com.baari.queue.ServiceHistory;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Trains, evaluates and promotes a clinic's ML model (PLAN §8.6, ADR 0013). Nightly at
 * {@code baari.ml.train-hour}:30 clinic time, and on demand from the clinic admin's accuracy page.
 *
 * <ol>
 *   <li>Train on days D-60 to D-8, test on D-7 to D-1 (D is today, clinic time). Never a random split.
 *   <li>Skip if the training window has fewer than {@code min-training-samples} consultations.
 *   <li>Score each candidate on the test consultations, then replay the test days through the
 *       projector ({@link WaitReplay}) for the wait-time error.
 *   <li>Promote the best candidate only if its wait-time error beats the current champion's (or
 *       Rolling's, if there is none) by at least {@value #REQUIRED_GAIN_PERCENT}%. Every candidate is
 *       saved with its metrics either way.
 * </ol>
 */
@Service
public class MlTraining {

    private static final Logger log = LoggerFactory.getLogger(MlTraining.class);

    static final int REQUIRED_GAIN_PERCENT = 5;
    static final int TRAIN_DAYS_BACK = 60;
    static final int TEST_DAYS = 7;

    public enum Outcome {
        /** Too little history; nothing was trained. */
        TOO_FEW_SAMPLES,
        /** A candidate became the clinic's champion. */
        PROMOTED,
        /** No candidate was good enough; the clinic keeps what it had. */
        KEPT
    }

    /**
     * @param best the best candidate's name, or null if nothing was trained
     * @param bestWaitMaeMin its wait-time error on the test days, minutes
     * @param baseline what it had to beat: the champion's name or ROLLING
     */
    public record Result(
            Outcome outcome,
            int trainingSamples,
            String best,
            Double bestWaitMaeMin,
            String baseline,
            Double baselineWaitMaeMin) {}

    private final ServiceHistory history;
    private final QueueQueryService queues;
    private final QueueDirectory directory;
    private final ClinicAdminService clinics;
    private final MlModelStore store;
    private final EtaProperties eta;
    private final MlProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;

    MlTraining(
            ServiceHistory history,
            QueueQueryService queues,
            QueueDirectory directory,
            ClinicAdminService clinics,
            MlModelStore store,
            EtaProperties eta,
            MlProperties props,
            TransactionTemplate tx,
            Clock clock) {
        this.history = history;
        this.queues = queues;
        this.directory = directory;
        this.clinics = clinics;
        this.store = store;
        this.eta = eta;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
    }

    /** Runs every hour at minute 30 and trains the clinics whose local time is the training hour. */
    @Scheduled(cron = "${baari.ml.cron:0 30 * * * *}")
    void nightly() {
        Instant now = clock.instant();
        for (ClinicView clinic : directory.allClinics()) {
            ZonedDateTime local = now.atZone(clinic.timezone());
            Instant startOfDay =
                    local.toLocalDate().atStartOfDay(clinic.timezone()).toInstant();
            if (local.getHour() != props.trainHour() || store.trainedSince(clinic.id(), startOfDay)) {
                continue;
            }
            try {
                Result r = train(clinic.id(), clinic.timezone());
                log.info("ML training for clinic {}: {}", clinic.id(), r);
            } catch (RuntimeException e) {
                log.error("ML training for clinic {} failed: {}", clinic.id(), e.toString(), e);
            }
        }
    }

    /** The clinic admin's "train now" button. */
    public Result trainNow(StaffPrincipal caller) {
        if (caller.role() != StaffRole.CLINIC_ADMIN) {
            throw new ForbiddenException();
        }
        ClinicView clinic = clinics.myClinic(caller);
        return train(clinic.id(), clinic.timezone());
    }

    Result train(UUID clinicId, ZoneId zone) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, zone);
        LocalDate trainFrom = today.minusDays(TRAIN_DAYS_BACK);
        LocalDate testFrom = today.minusDays(TEST_DAYS);
        LocalDate trainTo = testFrom.minusDays(1);
        LocalDate testTo = today.minusDays(1);

        List<HistoricalToken> tokens = history.tokens(clinicId, trainFrom, testTo);
        List<HistoricalToken> trainTokens =
                tokens.stream().filter(t -> !t.serviceDate().isAfter(trainTo)).toList();
        List<HistoricalToken> testTokens =
                tokens.stream().filter(t -> !t.serviceDate().isBefore(testFrom)).toList();
        List<TrainingExamples.Example> trainExamples = TrainingExamples.from(trainTokens, zone);
        List<TrainingExamples.Example> testExamples = TrainingExamples.from(testTokens, zone);
        if (trainExamples.size() < props.minTrainingSamples() || testExamples.isEmpty()) {
            return new Result(Outcome.TOO_FEW_SAMPLES, trainExamples.size(), null, null, null, null);
        }

        List<ModelTrainer.Candidate> candidates = ModelTrainer.train(trainExamples, testExamples);
        ServiceTimeEstimator fallback = new DefaultEstimator(eta.defaultServiceMinutes() * 60);
        RollingStatsEstimator rolling = new RollingStatsEstimator(
                TrainingExamples.rollingSamples(trainTokens, testFrom, zone), eta.minSamples(), fallback);
        ServiceTimeEstimator rollingOrDefault = rolling.hasEnoughData() ? rolling : fallback;

        List<WaitReplay.Variant> variants = new ArrayList<>();
        variants.add(new WaitReplay.Variant(RollingStatsEstimator.NAME, rollingOrDefault, 1.0));
        var champion = store.championRef(clinicId).flatMap(store::load);
        champion.ifPresent(c -> variants.add(
                new WaitReplay.Variant("CHAMPION", new MlEstimator(c.model(), c.version(), rollingOrDefault), 1.0)));
        for (ModelTrainer.Candidate c : candidates) {
            variants.add(new WaitReplay.Variant(
                    c.model().algorithm(), new MlEstimator(c.model(), 0, rollingOrDefault), 1.0));
        }
        List<WaitReplay.Observation> observations = WaitReplay.replay(
                testTokens,
                variants,
                new WaitReplay.Settings(zone, eta.transitionGapSeconds(), queues.policySettings()));
        Map<String, WaitReplay.Stats> wait = new LinkedHashMap<>();
        for (WaitReplay.Variant v : variants) {
            wait.put(
                    v.name(),
                    WaitReplay.stats(observations.stream()
                            .filter(o -> o.variant().equals(v.name()))
                            .toList()));
        }

        String baseline = champion.isPresent() ? "CHAMPION" : RollingStatsEstimator.NAME;
        double baselineMae = wait.get(baseline).maeMin();
        ModelTrainer.Candidate best = candidates.stream()
                .min(java.util.Comparator.comparingDouble(
                        c -> wait.get(c.model().algorithm()).maeMin()))
                .orElseThrow();
        double bestMae = wait.get(best.model().algorithm()).maeMin();
        // NaN (no scored waits) never promotes: the comparison is false
        boolean promote = bestMae <= baselineMae * (100 - REQUIRED_GAIN_PERCENT) / 100.0;
        String baselineName = champion.map(c -> "ML v" + c.version()).orElse(RollingStatsEstimator.NAME);
        ModelTrainer.ServiceMetrics rollingService = ModelTrainer.score(rollingOrDefault, testExamples);

        tx.executeWithoutResult(status -> {
            if (promote) {
                store.retireChampion(clinicId);
            }
            int version = store.nextVersion(clinicId);
            for (ModelTrainer.Candidate c : candidates) {
                WaitReplay.Stats w = wait.get(c.model().algorithm());
                Map<String, Object> metrics = new LinkedHashMap<>();
                metrics.put("serviceMaeS", c.test().maeS());
                metrics.put("serviceMedianAeS", c.test().medianAeS());
                metrics.put("serviceP90AeS", c.test().p90AeS());
                metrics.put("rollingServiceMaeS", rollingService.maeS());
                metrics.put("waitMaeMin", w.maeMin());
                metrics.put("waitCoverage", w.coverage());
                metrics.put("waitPredictions", w.n());
                metrics.put(
                        "rollingWaitMaeMin",
                        wait.get(RollingStatsEstimator.NAME).maeMin());
                metrics.put("baseline", baselineName);
                metrics.put("baselineWaitMaeMin", baselineMae);
                metrics.put("testConsultations", testExamples.size());
                String modelStatus = promote && c == best ? "CHAMPION" : "REJECTED";
                store.save(
                        new MlModelStore.NewModel(
                                clinicId,
                                c.model(),
                                modelStatus,
                                now,
                                trainFrom,
                                trainTo,
                                testFrom,
                                testTo,
                                trainExamples.size(),
                                metrics),
                        version++);
            }
        });
        return new Result(
                promote ? Outcome.PROMOTED : Outcome.KEPT,
                trainExamples.size(),
                best.model().algorithm(),
                bestMae,
                baselineName,
                baselineMae);
    }
}
