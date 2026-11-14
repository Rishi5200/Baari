package com.baari.queue.internal;

import static com.baari.queue.TokenStatus.CALLED;
import static com.baari.queue.TokenStatus.CANCELLED;
import static com.baari.queue.TokenStatus.COMPLETED;
import static com.baari.queue.TokenStatus.EXPIRED;
import static com.baari.queue.TokenStatus.IN_CONSULTATION;
import static com.baari.queue.TokenStatus.NO_SHOW;
import static com.baari.queue.TokenStatus.WAITING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.baari.queue.ActorType;
import com.baari.queue.TokenFlag;
import com.baari.queue.TokenStatus;
import com.baari.queue.internal.TokenCommand.CallNext;
import com.baari.queue.internal.TokenCommand.CallSpecific;
import com.baari.queue.internal.TokenCommand.Cancel;
import com.baari.queue.internal.TokenCommand.CloseDay;
import com.baari.queue.internal.TokenCommand.Complete;
import com.baari.queue.internal.TokenCommand.MarkNoShow;
import com.baari.queue.internal.TokenCommand.Recall;
import com.baari.queue.internal.TokenCommand.SetPriority;
import com.baari.queue.internal.TokenCommand.Start;
import com.baari.queue.internal.Transition.Allowed;
import com.baari.queue.internal.Transition.Reason;
import com.baari.queue.internal.Transition.Rejected;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every status × every command (PLAN §12, Phase 2). The table below lists each move that is
 * allowed or refused for a special reason; any pair not in it must be ILLEGAL_TRANSITION.
 */
@Tag("unit")
class TokenStateMachineTest {

    private static final Instant CALLED_AT = Instant.parse("2026-11-14T05:00:00Z");
    private static final Duration GRACE = Duration.ofMinutes(5);
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 14);

    /** Named command variants, so test names read like "CALLED + noShow(early, forced)". */
    private static final Map<String, TokenCommand> COMMANDS = new LinkedHashMap<>();

    static {
        COMMANDS.put("callNext", new CallNext());
        COMMANDS.put("callSpecific", new CallSpecific());
        COMMANDS.put("start", new Start());
        COMMANDS.put("complete", new Complete());
        COMMANDS.put("noShow(after grace)", new MarkNoShow(CALLED_AT, CALLED_AT.plus(GRACE), GRACE, false));
        COMMANDS.put("noShow(early)", new MarkNoShow(CALLED_AT, CALLED_AT.plusSeconds(60), GRACE, false));
        COMMANDS.put("noShow(early, forced)", new MarkNoShow(CALLED_AT, CALLED_AT.plusSeconds(60), GRACE, true));
        COMMANDS.put("recall(same day)", new Recall(TODAY, TODAY));
        COMMANDS.put("recall(other day)", new Recall(TODAY.minusDays(1), TODAY));
        COMMANDS.put("cancel(patient)", new Cancel(ActorType.PATIENT));
        COMMANDS.put("cancel(staff)", new Cancel(ActorType.STAFF));
        COMMANDS.put("cancel(system)", new Cancel(ActorType.SYSTEM));
        COMMANDS.put("setPriority", new SetPriority());
        COMMANDS.put("closeDay", new CloseDay());
    }

    /** Key: "STATUS command". Written out by hand from PLAN §4, not derived from the code. */
    private static final Map<String, Transition> EXPECTED = Map.ofEntries(
            // WAITING
            Map.entry("WAITING callNext", allowed(CALLED)),
            Map.entry("WAITING callSpecific", allowed(CALLED, TokenFlag.OVERRIDE_CALL)),
            Map.entry("WAITING start", allowed(IN_CONSULTATION, TokenFlag.DIRECT_START)),
            Map.entry("WAITING cancel(patient)", allowed(CANCELLED)),
            Map.entry("WAITING cancel(staff)", allowed(CANCELLED)),
            Map.entry("WAITING setPriority", allowed(WAITING)),
            Map.entry("WAITING closeDay", allowed(EXPIRED)),
            // CALLED
            Map.entry("CALLED start", allowed(IN_CONSULTATION)),
            Map.entry("CALLED noShow(after grace)", allowed(NO_SHOW)),
            Map.entry("CALLED noShow(early)", rejected(Reason.GRACE_NOT_ELAPSED)),
            Map.entry("CALLED noShow(early, forced)", allowed(NO_SHOW, TokenFlag.FORCED_NO_SHOW)),
            Map.entry("CALLED cancel(patient)", allowed(CANCELLED)),
            Map.entry("CALLED cancel(staff)", allowed(CANCELLED)),
            Map.entry("CALLED closeDay", allowed(EXPIRED)),
            // IN_CONSULTATION
            Map.entry("IN_CONSULTATION complete", allowed(COMPLETED)),
            Map.entry("IN_CONSULTATION closeDay", allowed(COMPLETED, TokenFlag.AUTO_CLOSED)),
            // NO_SHOW
            Map.entry("NO_SHOW recall(same day)", allowed(WAITING)),
            Map.entry("NO_SHOW recall(other day)", rejected(Reason.RECALL_OTHER_DAY)),
            Map.entry("NO_SHOW cancel(staff)", allowed(CANCELLED)),
            Map.entry("NO_SHOW closeDay", allowed(EXPIRED))
            // COMPLETED, CANCELLED, EXPIRED are final: nothing is allowed from them.
            );

    static Stream<Arguments> everyStatusAndCommand() {
        List<Arguments> cases = new ArrayList<>();
        for (TokenStatus status : TokenStatus.values()) {
            COMMANDS.forEach((name, command) -> cases.add(Arguments.of(status, name, command)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} + {1}")
    @MethodSource("everyStatusAndCommand")
    void followsTheTable(TokenStatus from, String commandName, TokenCommand command) {
        Transition expected = EXPECTED.getOrDefault(from + " " + commandName, rejected(Reason.ILLEGAL_TRANSITION));

        assertThat(TokenStateMachine.apply(from, command)).isEqualTo(expected);
    }

    @Test
    void tableOnlyNamesRealStatusesAndCommands() {
        // Guards against a typo in EXPECTED silently turning an allowed move into "illegal".
        for (String key : EXPECTED.keySet()) {
            String[] parts = key.split(" ", 2);
            assertThat(TokenStatus.valueOf(parts[0])).isNotNull();
            assertThat(COMMANDS).containsKey(parts[1]);
        }
    }

    @Test
    void finalStatusesAcceptNothing() {
        for (TokenStatus status : List.of(COMPLETED, CANCELLED, EXPIRED)) {
            for (TokenCommand command : COMMANDS.values()) {
                assertThat(TokenStateMachine.apply(status, command))
                        .as("%s + %s", status, command)
                        .isEqualTo(rejected(Reason.ILLEGAL_TRANSITION));
            }
        }
    }

    @Test
    void noShowGraceBoundaryIsInclusive() {
        Instant oneSecondShort = CALLED_AT.plus(GRACE).minusSeconds(1);

        assertThat(TokenStateMachine.apply(CALLED, new MarkNoShow(CALLED_AT, oneSecondShort, GRACE, false)))
                .isEqualTo(rejected(Reason.GRACE_NOT_ELAPSED));
        assertThat(TokenStateMachine.apply(CALLED, new MarkNoShow(CALLED_AT, CALLED_AT.plus(GRACE), GRACE, false)))
                .isEqualTo(allowed(NO_SHOW));
    }

    @Test
    void forcingANoShowAfterGraceIsNotFlagged() {
        // The flag marks an early decision, not the use of the force option.
        MarkNoShow late = new MarkNoShow(CALLED_AT, CALLED_AT.plus(GRACE).plusSeconds(30), GRACE, true);

        assertThat(TokenStateMachine.apply(CALLED, late)).isEqualTo(allowed(NO_SHOW));
    }

    @Test
    void zeroGraceAllowsAnImmediateNoShow() {
        assertThat(TokenStateMachine.apply(CALLED, new MarkNoShow(CALLED_AT, CALLED_AT, Duration.ZERO, false)))
                .isEqualTo(allowed(NO_SHOW));
    }

    @Test
    void commandsRejectMissingFacts() {
        assertThatNullPointerException().isThrownBy(() -> new MarkNoShow(null, CALLED_AT, GRACE, false));
        assertThatNullPointerException().isThrownBy(() -> new MarkNoShow(CALLED_AT, null, GRACE, false));
        assertThatNullPointerException().isThrownBy(() -> new MarkNoShow(CALLED_AT, CALLED_AT, null, false));
        assertThatNullPointerException().isThrownBy(() -> new Recall(null, TODAY));
        assertThatNullPointerException().isThrownBy(() -> new Recall(TODAY, null));
        assertThatNullPointerException().isThrownBy(() -> new Cancel(null));
    }

    private static Transition allowed(TokenStatus to) {
        return new Allowed(to, Optional.empty());
    }

    private static Transition allowed(TokenStatus to, TokenFlag flag) {
        return new Allowed(to, Optional.of(flag));
    }

    private static Transition rejected(Reason reason) {
        return new Rejected(reason);
    }
}
