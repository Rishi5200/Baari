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
        List<String> nowServing = tokens
                .findByQueueDayIdAndStatusInOrderBySortKeyAscNumberAsc(
                        day.getId(), EnumSet.of(TokenStatus.CALLED, TokenStatus.IN_CONSULTATION))
                .stream()
                .sorted(LATEST_CALL_FIRST)
                .map(t -> TokenViews.displayCode(info, t))
                .toList();
        return new PublicTokenView(
                TokenViews.displayCode(info, token),
                token.getStatus(),
                position,
                position == null ? null : position - 1,
                nowServing,
                info.queue().name(),
                day.getStatus());
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
