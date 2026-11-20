package com.baari.queue;

import com.baari.clinic.DoctorView;
import com.baari.clinic.PublicQueue;
import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueInfo;
import com.baari.clinic.StaffPrincipal;
import com.baari.common.NotFoundException;
import com.baari.queue.internal.QueueDayEntity;
import com.baari.queue.internal.QueueDayRepository;
import com.baari.queue.internal.TokenEntity;
import com.baari.queue.internal.TokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Read-only views of a queue: the join page, the patient's token page and the staff board. */
@Service
public class QueueQueryService {

    private static final EnumSet<TokenStatus> FINISHED =
            EnumSet.of(TokenStatus.COMPLETED, TokenStatus.CANCELLED, TokenStatus.EXPIRED);

    /** Latest call first; a direct start has no call time, so its start time stands in. */
    private static final Comparator<TokenEntity> LATEST_CALL_FIRST = Comparator.comparing(
                    (TokenEntity t) -> t.getCalledAt() != null ? t.getCalledAt() : t.getConsultStartedAt(),
                    Comparator.nullsLast(Comparator.<Instant>naturalOrder()))
            .reversed();

    /** How many upcoming codes the TV shows (PLAN §2.2). */
    private static final int DISPLAY_NEXT = 5;

    private final QueueDirectory directory;
    private final QueueAccess access;
    private final QueueDayRepository days;
    private final TokenRepository tokens;
    private final QueueProperties settings;
    private final Clock clock;

    QueueQueryService(
            QueueDirectory directory,
            QueueAccess access,
            QueueDayRepository days,
            TokenRepository tokens,
            QueueProperties settings,
            Clock clock) {
        this.directory = directory;
        this.access = access;
        this.days = days;
        this.tokens = tokens;
        this.settings = settings;
        this.clock = clock;
    }

    /** The queue behind a join link, with today's status and how many are waiting. */
    @Transactional(readOnly = true)
    public PublicQueueView publicQueue(String clinicSlug, String queueSlug) {
        PublicQueue queue =
                directory.findBySlugs(clinicSlug, queueSlug).orElseThrow(() -> new NotFoundException("Queue"));
        Optional<QueueDayEntity> day = today(queue.info());
        int waiting = day.map(
                        d -> (int) tokens.countByQueueDayIdAndStatusIn(d.getId(), EnumSet.of(TokenStatus.WAITING)))
                .orElse(0);
        return new PublicQueueView(
                queue.info().queue().id(),
                queue.clinicName(),
                queue.info().queue().name(),
                queue.doctors().stream().map(DoctorView::name).toList(),
                day.map(QueueDayEntity::getStatus).orElse(null),
                waiting);
    }

    /** The patient's token page. The public code is the only key; an unknown one is "not found". */
    @Transactional(readOnly = true)
    public PublicTokenView publicToken(String publicCode) {
        Objects.requireNonNull(publicCode, "publicCode");
        TokenEntity token = tokens.findByPublicCode(publicCode).orElseThrow(() -> new NotFoundException("Token"));
        QueueDayEntity day = days.findById(token.getQueueDayId()).orElseThrow();
        QueueInfo info = directory.find(day.getQueueId()).orElseThrow(() -> new NotFoundException("Token"));

        Integer position = null;
        if (token.getStatus() == TokenStatus.WAITING) {
            List<TokenEntity> order = TokenViews.inCallOrder(waiting(day), day, settings.policySettings());
            position = order.indexOf(token) + 1;
        }
        Map<UUID, String> rooms = directory.roomLabels(day.getQueueId());
        List<String> nowServing = servingNow(day).stream()
                .map(t -> TokenViews.displayCode(info, t))
                .toList();
        return new PublicTokenView(
                TokenViews.displayCode(info, token),
                token.getStatus(),
                position,
                position == null ? null : position - 1,
                nowServing,
                info.queue().name(),
                day.getStatus(),
                token.getServedByDoctorId() == null ? null : rooms.get(token.getServedByDoctorId()));
    }

    /** The queue a token belongs to, so the live stream knows which changes to listen for. */
    @Transactional(readOnly = true)
    public UUID queueIdOfToken(String publicCode) {
        Objects.requireNonNull(publicCode, "publicCode");
        TokenEntity token = tokens.findByPublicCode(publicCode).orElseThrow(() -> new NotFoundException("Token"));
        return days.findById(token.getQueueDayId()).orElseThrow().getQueueId();
    }

    /** Which token and queue a public code belongs to. An unknown code is "not found". */
    @Transactional(readOnly = true)
    public TokenLocator locate(String publicCode) {
        Objects.requireNonNull(publicCode, "publicCode");
        return tokens.findRefByPublicCode(publicCode)
                .map(ref -> new TokenLocator(ref.tokenId(), ref.queueId(), ref.clinicId()))
                .orElseThrow(() -> new NotFoundException("Token"));
    }

    /** The queue a queue day belongs to (events carry the day id only). */
    @Transactional(readOnly = true)
    public Optional<UUID> queueIdOfDay(UUID queueDayId) {
        return days.findById(queueDayId).map(QueueDayEntity::getQueueId);
    }

    /** Tokens that were open when the day closed. Used to tell patients the queue has closed. */
    @Transactional(readOnly = true)
    public List<UUID> expiredTokenIds(UUID queueDayId) {
        return tokens
                .findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(queueDayId, EnumSet.of(TokenStatus.EXPIRED))
                .stream()
                .map(TokenEntity::getId)
                .toList();
    }

    /** A token's place and state for a notification, or empty if it no longer exists. */
    @Transactional(readOnly = true)
    public Optional<TokenAlertView> alertView(UUID tokenId) {
        return tokens.findById(tokenId)
                .flatMap(token -> days.findById(token.getQueueDayId())
                        .flatMap(day -> directory
                                .find(day.getQueueId())
                                .map(info -> new TokenAlertView(
                                        token.getId(),
                                        day.getQueueId(),
                                        day.getId(),
                                        new DisplayCode(info.queue().displayCodePrefix(), token.getNumber()).toString(),
                                        token.getStatus()))));
    }

    /**
     * Today's state of a queue for the ETA module, or empty if today's day was never opened. No
     * caller check: callers (the {@code eta} module) get here only after their own checks.
     */
    @Transactional(readOnly = true)
    public Optional<QueueLiveState> liveState(UUID queueId) {
        QueueInfo info = directory.find(queueId).orElseThrow(() -> new NotFoundException("Queue"));
        return today(info)
                .map(day -> new QueueLiveState(
                        day.getClinicId(),
                        queueId,
                        day.getId(),
                        day.getStatus(),
                        day.toView().lastEventSeq(),
                        new NextTokenPolicy.State(day.getConsecutivePriorityServed()),
                        settings.policySettings(),
                        tokens.findByQueueDayIdOrderByNumberAsc(day.getId()).stream()
                                .map(t -> new QueueLiveState.LiveToken(
                                        t.toTokenView(),
                                        t.getVisitType(),
                                        t.getCalledAt(),
                                        t.getConsultStartedAt(),
                                        t.getServedByDoctorId()))
                                .toList()));
    }

    /**
     * The TV board. Open to anyone holding the queue's display key; a wrong key and an unknown
     * queue both answer "not found" so the screen's URL can't be used to probe ids.
     */
    @Transactional(readOnly = true)
    public DisplayBoard displayBoard(UUID queueId, String displayKey) {
        if (!directory.displayKeyMatches(queueId, displayKey)) {
            throw new NotFoundException("Display");
        }
        QueueInfo info = directory.find(queueId).orElseThrow(() -> new NotFoundException("Display"));
        Optional<QueueDayEntity> found = today(info);
        if (found.isEmpty()) {
            return new DisplayBoard(info.queue().name(), null, List.of(), List.of(), 0);
        }
        QueueDayEntity day = found.get();
        Map<UUID, String> rooms = directory.roomLabels(queueId);
        List<TokenEntity> waiting = TokenViews.inCallOrder(waiting(day), day, settings.policySettings());
        return new DisplayBoard(
                info.queue().name(),
                day.getStatus(),
                servingNow(day).stream()
                        .map(t -> new DisplayBoard.Serving(
                                TokenViews.displayCode(info, t),
                                t.getServedByDoctorId() == null ? null : rooms.get(t.getServedByDoctorId())))
                        .toList(),
                waiting.stream()
                        .limit(DISPLAY_NEXT)
                        .map(t -> TokenViews.displayCode(info, t))
                        .toList(),
                waiting.size());
    }

    private List<TokenEntity> servingNow(QueueDayEntity day) {
        return tokens
                .findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
                        day.getId(), EnumSet.of(TokenStatus.CALLED, TokenStatus.IN_CONSULTATION))
                .stream()
                .sorted(LATEST_CALL_FIRST)
                .toList();
    }

    /** Today's board for the staff console. */
    @Transactional(readOnly = true)
    public Board board(StaffPrincipal caller, UUID queueId) {
        QueueInfo info = access.requireStaff(caller, queueId);
        Optional<QueueDayEntity> found = today(info);
        if (found.isEmpty()) {
            return new Board(null, List.of(), List.of(), List.of(), List.of(), List.of());
        }
        QueueDayEntity day = found.get();
        List<TokenEntity> all = tokens.findByQueueDayIdOrderByNumberAsc(day.getId());
        return new Board(
                day.toView(),
                views(info, TokenViews.inCallOrder(waiting(day), day, settings.policySettings())),
                views(info, withStatus(all, TokenStatus.CALLED)),
                views(info, withStatus(all, TokenStatus.IN_CONSULTATION)),
                views(info, withStatus(all, TokenStatus.NO_SHOW)),
                views(
                        info,
                        all.stream()
                                .filter(t -> FINISHED.contains(t.getStatus()))
                                .toList()));
    }

    private List<TokenEntity> waiting(QueueDayEntity day) {
        return tokens.findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
                day.getId(), EnumSet.of(TokenStatus.WAITING));
    }

    private static List<TokenEntity> withStatus(List<TokenEntity> tokens, TokenStatus status) {
        return tokens.stream().filter(t -> t.getStatus() == status).toList();
    }

    private static List<StaffTokenView> views(QueueInfo info, List<TokenEntity> tokens) {
        return tokens.stream().map(t -> TokenViews.staff(info, t)).toList();
    }

    private Optional<QueueDayEntity> today(QueueInfo info) {
        LocalDate date = clock.instant().atZone(info.timezone()).toLocalDate();
        return days.findByQueueIdAndServiceDate(info.queue().id(), date);
    }
}
