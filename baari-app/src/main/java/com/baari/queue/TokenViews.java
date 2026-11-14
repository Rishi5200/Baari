package com.baari.queue;

import com.baari.clinic.QueueInfo;
import com.baari.queue.internal.QueueDayEntity;
import com.baari.queue.internal.TokenEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Turning token entities into read models, shared by the queue services. */
final class TokenViews {

    private TokenViews() {}

    static String displayCode(QueueInfo info, TokenEntity token) {
        return new DisplayCode(info.queue().displayCodePrefix(), token.getNumber()).toString();
    }

    /**
     * Waiting tokens in the order they will be called, from the same policy call-next uses (so a
     * patient's position is always the real one). Index 0 is called next.
     */
    static List<TokenEntity> inCallOrder(
            List<TokenEntity> waiting, QueueDayEntity day, NextTokenPolicy.Settings settings) {
        Map<UUID, TokenEntity> byId =
                waiting.stream().collect(Collectors.toMap(TokenEntity::getId, Function.identity()));
        return NextTokenPolicy.projectOrder(
                        new QueueSnapshot(
                                waiting.stream().map(TokenEntity::toTokenView).toList()),
                        new NextTokenPolicy.State(day.getConsecutivePriorityServed()),
                        settings)
                .stream()
                .map(view -> byId.get(view.id()))
                .toList();
    }

    static StaffTokenView staff(QueueInfo info, TokenEntity token) {
        return new StaffTokenView(
                token.getId(),
                displayCode(info, token),
                token.getNumber(),
                token.getStatus(),
                token.getPriority(),
                token.getVisitType(),
                token.getSource(),
                token.getPatientName(),
                token.getPhoneLast4(),
                token.getFlags().stream().map(TokenFlag::valueOf).toList(),
                token.getServedByDoctorId(),
                token.getJoinedAt(),
                token.getCalledAt(),
                token.getConsultStartedAt(),
                token.getCompletedAt(),
                token.getCancelledAt());
    }
}
