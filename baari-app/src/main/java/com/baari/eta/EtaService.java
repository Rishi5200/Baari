package com.baari.eta;

import com.baari.clinic.DoctorView;
import com.baari.clinic.PublicQueue;
import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.queue.Board;
import com.baari.queue.PublicTokenView;
import com.baari.queue.QueueDayStatus;
import com.baari.queue.QueueLiveState;
import com.baari.queue.QueueLiveState.LiveToken;
import com.baari.queue.QueueQueryService;
import com.baari.queue.QueueSnapshot;
import com.baari.queue.ServiceHistory;
import com.baari.queue.ServiceSample;
import com.baari.queue.TokenEvents.ConsultCompleted;
import com.baari.queue.TokenLocator;
import com.baari.queue.TokenStatus;
import com.baari.queue.VisitType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Wait-time estimates (PLAN §8.2). Reads the queue's state, picks the estimator (rolling
 * statistics once the clinic has enough finished consultations, the default length before that),
 * runs {@link QueueProjector}, and for patients smooths and rounds the result.
 *
 * <p>Caching (ADR 0010): the projection of a queue is reused while the queue's event sequence number
 * is unchanged and it is younger than {@code projectionCacheSeconds}, so many open token pages cost
 * one projection per change. The last range shown to each patient is kept in memory, per instance.
 */
@Service
public class EtaService {

    /** How long a clinic's consultation history is reused when nothing has finished in between. */
    private static final Duration HISTORY_TTL = Duration.ofMinutes(1);

    private final QueueQueryService queues;
    private final ServiceHistory history;
    private final QueueDirectory directory;
    private final EtaProperties props;
    private final CalibrationStore calibration;
    private final Clock clock;

    private final Map<UUID, Projected> projections = new ConcurrentHashMap<>();
    private final Map<UUID, LoadedEstimator> estimators = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, Held>> held = new ConcurrentHashMap<>();

    EtaService(
            QueueQueryService queues,
            ServiceHistory history,
            QueueDirectory directory,
            EtaProperties props,
            CalibrationStore calibration,
            Clock clock) {
        this.queues = queues;
        this.history = history;
        this.directory = directory;
        this.props = props;
        this.calibration = calibration;
        this.clock = clock;
    }

    /** The raw projection of a queue, with the state it was made from and the estimator used. */
    record Projected(
            String estimator, QueueLiveState state, Instant at, Map<UUID, QueueProjector.Projection> byToken) {}

    /** A clinic's estimator and range calibration factor, reloaded at most once a minute. */
    private record LoadedEstimator(Instant loadedAt, ServiceTimeEstimator estimator, double calibration) {}

    /** The range a patient was last shown, and when. */
    private record Held(Instant shownAt, EtaSmoother.Shown shown) {}

    /** The patient's token page with a wait estimate. An unknown code is "not found". */
    public PatientTokenView patientView(String publicCode) {
        PublicTokenView base = queues.publicToken(publicCode);
        EtaDisplay eta = null;
        if (base.status() == TokenStatus.WAITING) {
            TokenLocator where = queues.locate(publicCode);
            eta = displayFor(where.queueId(), where.tokenId()).orElse(null);
        }
        return new PatientTokenView(base, eta);
    }

    /** The staff board with the predicted start of every waiting patient. Checks the caller's access. */
    public StaffBoardView staffBoard(StaffPrincipal caller, UUID queueId) {
        Board board = queues.board(caller, queueId);
        Map<UUID, TokenEstimate> estimates = new HashMap<>();
        projectQueue(queueId)
                .ifPresent(p -> p.byToken()
                        .forEach((tokenId, projection) -> estimates.put(
                                tokenId,
                                new TokenEstimate(
                                        projection.position(),
                                        projection.expectedStartAt(),
                                        projection.waitSeconds()))));
        return new StaffBoardView(board, estimates);
    }

    /** The place in line of every waiting patient of a queue today (1 = next). Empty if the day is closed. */
    public Map<UUID, TokenEstimate> estimates(UUID queueId) {
        Map<UUID, TokenEstimate> result = new HashMap<>();
        projectQueue(queueId)
                .ifPresent(p -> p.byToken()
                        .forEach((tokenId, projection) -> result.put(
                                tokenId,
                                new TokenEstimate(
                                        projection.position(),
                                        projection.expectedStartAt(),
                                        projection.waitSeconds()))));
        return result;
    }

    /** The wait as a patient would read it on the token page (same smoothing), or empty if not waiting. */
    public Optional<EtaDisplay> displayOf(UUID queueId, UUID tokenId) {
        return displayFor(queueId, tokenId);
    }

    /**
     * The raw projection of a queue's waiting patients, or empty if today's day was never opened.
     * Closed days have none, and neither do queues with no doctor serving.
     */
    Optional<Projected> projectQueue(UUID queueId) {
        Optional<QueueLiveState> live = queues.liveState(queueId);
        if (live.isEmpty()) {
            return Optional.empty();
        }
        QueueLiveState state = live.get();
        Instant now = clock.instant();
        Projected cached = projections.get(queueId);
        if (cached != null
                && cached.state().seq() == state.seq()
                && cached.state().queueDayId().equals(state.queueDayId())
                && Duration.between(cached.at(), now).compareTo(Duration.ofSeconds(props.projectionCacheSeconds()))
                        < 0) {
            return Optional.of(cached);
        }
        PublicQueue queue = directory.findPublic(queueId).orElseThrow();
        ZoneId zone = queue.info().timezone();
        LoadedEstimator loaded = loaded(state.clinicId(), zone, now);
        ServiceTimeEstimator estimator = loaded.estimator();

        Map<UUID, QueueProjector.Projection> byToken = new LinkedHashMap<>();
        if (state.status() != QueueDayStatus.CLOSED) {
            QueueProjector.project(
                            input(state, queue, zone, now),
                            estimator,
                            new QueueProjector.Params(
                                    props.transitionGapSeconds(), props.noShowRate(), loaded.calibration()))
                    .forEach(p -> byToken.put(p.tokenId(), p));
        }
        Projected fresh = new Projected(estimator.name(), state, now, byToken);
        projections.put(queueId, fresh);
        Map<UUID, Held> queueHeld = held.get(queueId);
        if (queueHeld != null) {
            queueHeld.keySet().retainAll(byToken.keySet());
        }
        return Optional.of(fresh);
    }

    /** The estimator in use for a clinic: its own statistics if there is enough history, else the default. */
    ServiceTimeEstimator estimatorFor(UUID clinicId, ZoneId zone, Instant now) {
        return loaded(clinicId, zone, now).estimator();
    }

    private LoadedEstimator loaded(UUID clinicId, ZoneId zone, Instant now) {
        LoadedEstimator loaded = estimators.get(clinicId);
        if (loaded == null || Duration.between(loaded.loadedAt(), now).compareTo(HISTORY_TTL) >= 0) {
            List<ServiceSample> samples =
                    history.recent(clinicId, now.minus(Duration.ofDays(props.historyDays())), props.historyLimit());
            DefaultEstimator fallback = new DefaultEstimator(props.defaultServiceMinutes() * 60);
            RollingStatsEstimator rolling = new RollingStatsEstimator(
                    samples.stream()
                            .map(s -> new RollingStatsEstimator.Sample(
                                    s.doctorId(),
                                    s.visitType(),
                                    s.startedAt().atZone(zone).getHour(),
                                    s.serviceSeconds()))
                            .toList(),
                    props.minSamples(),
                    fallback);
            loaded = new LoadedEstimator(
                    now, rolling.hasEnoughData() ? rolling : fallback, calibration.factor(clinicId));
            estimators.put(clinicId, loaded);
        }
        return loaded;
    }

    /** A finished consultation changes the clinic's statistics, so the next estimate reloads them. */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onConsultCompleted(ConsultCompleted event) {
        estimators.remove(event.clinicId());
    }

    private Optional<EtaDisplay> displayFor(UUID queueId, UUID tokenId) {
        return projectQueue(queueId).map(p -> p.byToken().get(tokenId)).map(p -> smoothed(queueId, p));
    }

    /**
     * Applies hysteresis. The range shown earlier is moved forward by the time that has passed, so
     * it counts down by itself; a new prediction replaces it only if it differs enough (PLAN §8.5).
     */
    private EtaDisplay smoothed(UUID queueId, QueueProjector.Projection p) {
        Instant now = clock.instant();
        EtaSmoother.Shown candidate = new EtaSmoother.Shown(p.rangeMinSeconds(), p.rangeMaxSeconds(), p.position());
        EtaSmoother.Shown[] shown = new EtaSmoother.Shown[1];
        held.computeIfAbsent(queueId, id -> new ConcurrentHashMap<>()).compute(p.tokenId(), (id, previous) -> {
            EtaSmoother.Shown aged = previous == null ? null : age(previous, now);
            shown[0] = EtaSmoother.smooth(aged, candidate, EtaSmoother.Settings.DEFAULT);
            return shown[0] == aged ? previous : new Held(now, shown[0]);
        });
        return EtaDisplay.of(shown[0].minSeconds(), shown[0].maxSeconds());
    }

    private static EtaSmoother.Shown age(Held held, Instant now) {
        int elapsed = (int) Math.max(0, Duration.between(held.shownAt(), now).toSeconds());
        EtaSmoother.Shown s = held.shown();
        return new EtaSmoother.Shown(
                Math.max(0, s.minSeconds() - elapsed), Math.max(0, s.maxSeconds() - elapsed), s.position());
    }

    private static QueueProjector.Input input(QueueLiveState state, PublicQueue queue, ZoneId zone, Instant now) {
        List<LiveToken> inConsultation = state.tokens().stream()
                .filter(t -> t.view().status() == TokenStatus.IN_CONSULTATION)
                .sorted(Comparator.comparing(
                        LiveToken::consultStartedAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .toList();

        // One server per active doctor (SINGLE queues have just one); a doctor with a patient in
        // the room is busy until that patient is done. A doctor who is no longer active but still
        // has someone inside stays a server until they finish.
        List<UUID> doctorIds =
                new ArrayList<>(queue.doctors().stream().map(DoctorView::id).toList());
        if (queue.info().queue().mode() == QueueMode.SINGLE && doctorIds.size() > 1) {
            doctorIds = new ArrayList<>(doctorIds.subList(0, 1));
        }
        for (LiveToken t : inConsultation) {
            if (t.servedByDoctorId() != null && !doctorIds.contains(t.servedByDoctorId())) {
                doctorIds.add(t.servedByDoctorId());
            }
        }
        List<QueueProjector.Server> servers = new ArrayList<>();
        for (UUID doctorId : doctorIds) {
            List<LiveToken> consults = inConsultation.stream()
                    .filter(t -> doctorId.equals(t.servedByDoctorId()))
                    .toList();
            // the doctor's session so far, from every consultation of theirs that started today (ML features)
            Sessions.SoFar session = Sessions.soFar(
                    state.tokens().stream()
                            .filter(t -> doctorId.equals(t.servedByDoctorId()) && t.consultStartedAt() != null)
                            .map(LiveToken::consultStartedAt)
                            .sorted()
                            .toList(),
                    now);
            if (consults.isEmpty()) {
                servers.add(new QueueProjector.Server(doctorId, null, session));
            }
            for (LiveToken t : consults) {
                servers.add(new QueueProjector.Server(
                        doctorId,
                        new QueueProjector.Consult(t.view().id(), t.visitType(), t.consultStartedAt()),
                        session));
            }
        }

        List<QueueProjector.Called> called = state.tokens().stream()
                .filter(t -> t.view().status() == TokenStatus.CALLED)
                .map(t -> new QueueProjector.Called(t.view().id(), t.visitType(), t.calledAt(), t.servedByDoctorId()))
                .toList();
        List<LiveToken> waiting = state.tokens().stream()
                .filter(t -> t.view().status() == TokenStatus.WAITING)
                .toList();
        Map<UUID, VisitType> visitTypes = new HashMap<>();
        waiting.forEach(t -> visitTypes.put(t.view().id(), t.visitType()));
        return new QueueProjector.Input(
                now,
                zone,
                servers,
                called,
                new QueueSnapshot(waiting.stream().map(LiveToken::view).toList()),
                visitTypes,
                state.policyState(),
                state.policySettings());
    }
}
