package com.baari.queue;

import static com.baari.queue.Priority.EMERGENCY;
import static com.baari.queue.Priority.NORMAL;
import static com.baari.queue.Priority.PRIORITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.baari.queue.NextTokenPolicy.Decision;
import com.baari.queue.NextTokenPolicy.Settings;
import com.baari.queue.NextTokenPolicy.State;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class NextTokenPolicyTest {

    private static final Instant JOINED = Instant.parse("2026-11-14T04:00:00Z");
    /** The defaults from PLAN §14. */
    private static final Settings DEFAULTS = new Settings(2, 2);

    private static TokenView token(int number, Priority priority) {
        return token(number, priority, TokenStatus.WAITING, TokenView.initialSortKey(number));
    }

    private static TokenView token(int number, Priority priority, TokenStatus status, long sortKey) {
        return new TokenView(new UUID(0, number), number, priority, status, sortKey, JOINED.plusSeconds(number));
    }

    private static QueueSnapshot queue(TokenView... tokens) {
        return new QueueSnapshot(Arrays.asList(tokens));
    }

    private static List<Integer> numbers(List<TokenView> tokens) {
        return tokens.stream().map(TokenView::number).toList();
    }

    private static List<Integer> projectedNumbers(QueueSnapshot queue, State state, Settings settings) {
        return numbers(NextTokenPolicy.projectOrder(queue, state, settings));
    }

    @Nested
    class Next {

        @Test
        void nobodyWaitingMeansNobodyIsCalled() {
            QueueSnapshot queue = queue(
                    token(1, NORMAL, TokenStatus.COMPLETED, 1000),
                    token(2, EMERGENCY, TokenStatus.CALLED, 2000),
                    token(3, PRIORITY, TokenStatus.NO_SHOW, 3000));

            assertThat(NextTokenPolicy.next(queue, State.INITIAL, DEFAULTS)).isEmpty();
            assertThat(NextTokenPolicy.next(queue(), State.INITIAL, DEFAULTS)).isEmpty();
        }

        @Test
        void normalTokensAreFirstInFirstOut() {
            QueueSnapshot queue = queue(token(3, NORMAL), token(1, NORMAL), token(2, NORMAL));

            Decision decision =
                    NextTokenPolicy.next(queue, State.INITIAL, DEFAULTS).orElseThrow();

            assertThat(decision.token().number()).isEqualTo(1);
            assertThat(decision.stateAfter()).isEqualTo(State.INITIAL);
        }

        @Test
        void emergencyIsAlwaysNextAndLeavesTheCounterAlone() {
            QueueSnapshot queue = queue(token(1, NORMAL), token(2, PRIORITY), token(5, EMERGENCY), token(4, EMERGENCY));

            Decision decision =
                    NextTokenPolicy.next(queue, new State(1), DEFAULTS).orElseThrow();

            assertThat(decision.token().number()).isEqualTo(4);
            assertThat(decision.stateAfter()).isEqualTo(new State(1));
        }

        @Test
        void priorityGoesAheadOfNormalAndCountsUp() {
            QueueSnapshot queue = queue(token(1, NORMAL), token(2, PRIORITY));

            Decision decision =
                    NextTokenPolicy.next(queue, State.INITIAL, DEFAULTS).orElseThrow();

            assertThat(decision.token().number()).isEqualTo(2);
            assertThat(decision.stateAfter()).isEqualTo(new State(1));
        }

        @Test
        void afterTheCapANormalPatientIsServedAndTheCounterResets() {
            QueueSnapshot queue = queue(token(1, NORMAL), token(2, PRIORITY));

            Decision decision =
                    NextTokenPolicy.next(queue, new State(2), DEFAULTS).orElseThrow();

            assertThat(decision.token().number()).isEqualTo(1);
            assertThat(decision.stateAfter()).isEqualTo(State.INITIAL);
        }

        @Test
        void priorityIsServedWhenNoNormalIsWaitingWhateverTheCap() {
            QueueSnapshot queue = queue(token(2, PRIORITY), token(1, NORMAL, TokenStatus.CALLED, 1000));

            Decision decision =
                    NextTokenPolicy.next(queue, new State(5), DEFAULTS).orElseThrow();

            assertThat(decision.token().number()).isEqualTo(2);
            assertThat(decision.stateAfter()).isEqualTo(new State(6));
        }
    }

    @Nested
    class Projection {

        @Test
        void priorityIsCappedAtNInARowThenOneNormal() {
            QueueSnapshot queue = queue(
                    token(1, NORMAL),
                    token(2, NORMAL),
                    token(3, NORMAL),
                    token(4, PRIORITY),
                    token(5, PRIORITY),
                    token(6, PRIORITY),
                    token(7, PRIORITY),
                    token(8, PRIORITY));

            assertThat(projectedNumbers(queue, State.INITIAL, DEFAULTS)).containsExactly(4, 5, 1, 6, 7, 2, 8, 3);
        }

        @Test
        void emergenciesComeFirstInTheirOwnLineOrder() {
            QueueSnapshot queue = queue(token(1, NORMAL), token(2, EMERGENCY), token(3, PRIORITY), token(4, EMERGENCY));

            assertThat(projectedNumbers(queue, State.INITIAL, DEFAULTS)).containsExactly(2, 4, 3, 1);
        }

        @Test
        void storedCounterIsRespected() {
            // Two PRIORITY patients were just called, so the waiting NORMAL patient goes first.
            QueueSnapshot queue = queue(token(1, NORMAL), token(2, PRIORITY), token(3, PRIORITY));

            assertThat(projectedNumbers(queue, new State(2), DEFAULTS)).containsExactly(1, 2, 3);
        }

        @Test
        void capOfZeroMeansNormalPatientsAlwaysGoFirst() {
            QueueSnapshot queue = queue(token(1, PRIORITY), token(2, NORMAL), token(3, NORMAL));

            assertThat(projectedNumbers(queue, State.INITIAL, new Settings(0, 2)))
                    .containsExactly(2, 3, 1);
        }

        @Test
        void onlyWaitingTokensAreProjected() {
            QueueSnapshot queue = queue(
                    token(1, NORMAL, TokenStatus.IN_CONSULTATION, 1000),
                    token(2, NORMAL, TokenStatus.CALLED, 2000),
                    token(3, NORMAL),
                    token(4, NORMAL, TokenStatus.NO_SHOW, 4000),
                    token(5, NORMAL, TokenStatus.CANCELLED, 5000));

            assertThat(projectedNumbers(queue, State.INITIAL, DEFAULTS)).containsExactly(3);
        }

        @Test
        void projectionMatchesCallingNextOnACopy() {
            List<TokenView> copy = new ArrayList<>(List.of(
                    token(1, NORMAL),
                    token(2, PRIORITY),
                    token(3, NORMAL),
                    token(4, PRIORITY),
                    token(5, EMERGENCY),
                    token(6, PRIORITY),
                    token(7, NORMAL),
                    token(8, NORMAL, TokenStatus.WAITING, 1001)));
            QueueSnapshot original = new QueueSnapshot(copy);

            // Simulate the real calls: each called token leaves WAITING, and the state is carried.
            List<Integer> called = new ArrayList<>();
            State state = State.INITIAL;
            Optional<Decision> decision;
            while ((decision = NextTokenPolicy.next(new QueueSnapshot(copy), state, DEFAULTS)).isPresent()) {
                TokenView picked = decision.get().token();
                called.add(picked.number());
                copy.set(
                        copy.indexOf(picked),
                        token(picked.number(), picked.priority(), TokenStatus.CALLED, picked.sortKey()));
                state = decision.get().stateAfter();
            }

            assertThat(projectedNumbers(original, State.INITIAL, DEFAULTS)).isEqualTo(called);
        }

        @Test
        void everyWaitingTokenAppearsExactlyOnceInAThousandRandomQueues() {
            Random random = new Random(20261114L);
            for (int run = 0; run < 1000; run++) {
                List<TokenView> tokens = randomTokens(random);
                Settings settings = new Settings(random.nextInt(4), 1 + random.nextInt(3));
                State state = new State(random.nextInt(4));
                QueueSnapshot queue = new QueueSnapshot(tokens);
                List<TokenView> waiting = tokens.stream()
                        .filter(t -> t.status() == TokenStatus.WAITING)
                        .toList();

                List<TokenView> order = NextTokenPolicy.projectOrder(queue, state, settings);

                String context = "run " + run + ", settings " + settings + ", state " + state;
                assertThat(order).as(context).hasSameSizeAs(waiting).containsExactlyInAnyOrderElementsOf(waiting);
                assertOrderRules(order, state, settings, context);
            }
        }

        private static List<TokenView> randomTokens(Random random) {
            Priority[] priorities = {NORMAL, NORMAL, NORMAL, NORMAL, PRIORITY, PRIORITY, EMERGENCY};
            TokenStatus[] statuses = {
                TokenStatus.WAITING, TokenStatus.WAITING, TokenStatus.WAITING, TokenStatus.WAITING,
                TokenStatus.CALLED, TokenStatus.COMPLETED, TokenStatus.NO_SHOW, TokenStatus.CANCELLED
            };
            int count = random.nextInt(40);
            List<TokenView> tokens = new ArrayList<>(count);
            for (int number = 1; number <= count; number++) {
                // Now and then a recalled token, sitting just behind an earlier one.
                long sortKey = random.nextInt(8) == 0
                        ? TokenView.initialSortKey(1 + random.nextInt(number)) + 1
                        : TokenView.initialSortKey(number);
                tokens.add(token(
                        number,
                        priorities[random.nextInt(priorities.length)],
                        statuses[random.nextInt(statuses.length)],
                        sortKey));
            }
            return tokens;
        }

        /**
         * Checks the PLAN §5 rules on a finished order without calling the policy: emergencies
         * first, line order inside each priority, and no more PRIORITY patients in a row than the
         * cap allows while a NORMAL patient is still waiting.
         */
        private static void assertOrderRules(List<TokenView> order, State state, Settings settings, String context) {
            int firstNonEmergency = 0;
            while (firstNonEmergency < order.size()
                    && order.get(firstNonEmergency).priority() == EMERGENCY) {
                firstNonEmergency++;
            }
            for (int i = firstNonEmergency; i < order.size(); i++) {
                assertThat(order.get(i).priority()).as(context).isNotEqualTo(EMERGENCY);
            }

            for (Priority priority : Priority.values()) {
                List<TokenView> line =
                        order.stream().filter(t -> t.priority() == priority).toList();
                for (int i = 1; i < line.size(); i++) {
                    TokenView before = line.get(i - 1);
                    TokenView after = line.get(i);
                    boolean inLine = before.sortKey() < after.sortKey()
                            || (before.sortKey() == after.sortKey() && before.number() < after.number());
                    assertThat(inLine)
                            .as(context + ": " + before + " before " + after)
                            .isTrue();
                }
            }

            int inARow = state.consecutivePriorityServed();
            for (int i = firstNonEmergency; i < order.size(); i++) {
                TokenView token = order.get(i);
                boolean normalStillWaiting =
                        order.subList(i, order.size()).stream().anyMatch(t -> t.priority() == NORMAL);
                if (token.priority() == PRIORITY) {
                    if (normalStillWaiting) {
                        assertThat(inARow).as(context + " at " + i).isLessThan(settings.maxConsecutivePriority());
                    }
                    inARow++;
                } else {
                    inARow = 0;
                }
            }
        }
    }

    @Nested
    class Recall {

        private final TokenView noShow = token(2, NORMAL, TokenStatus.NO_SHOW, 2000);

        @Test
        void insertsBehindExactlyTheOffsetNormalTokens() {
            QueueSnapshot queue = queue(noShow, token(3, NORMAL), token(4, NORMAL), token(5, NORMAL));

            long key = NextTokenPolicy.recallSortKey(queue, noShow, DEFAULTS);

            assertThat(key).isEqualTo(4001);
            TokenView recalled = token(2, NORMAL, TokenStatus.WAITING, key);
            QueueSnapshot after = queue(recalled, token(3, NORMAL), token(4, NORMAL), token(5, NORMAL));
            assertThat(projectedNumbers(after, State.INITIAL, DEFAULTS)).containsExactly(3, 4, 2, 5);
        }

        @Test
        void countsOnlyWaitingNormalTokens() {
            QueueSnapshot queue = queue(
                    noShow,
                    token(3, NORMAL, TokenStatus.CALLED, 3000),
                    token(4, PRIORITY),
                    token(5, EMERGENCY),
                    token(6, NORMAL),
                    token(7, NORMAL),
                    token(8, NORMAL));

            assertThat(NextTokenPolicy.recallSortKey(queue, noShow, DEFAULTS)).isEqualTo(7001);
        }

        @Test
        void goesToTheEndWhenFewerNormalsAreWaiting() {
            QueueSnapshot queue = queue(noShow, token(3, NORMAL), token(9, PRIORITY));

            assertThat(NextTokenPolicy.recallSortKey(queue, noShow, DEFAULTS)).isEqualTo(9001);
        }

        @Test
        void keepsItsOwnKeyWhenNobodyIsWaiting() {
            QueueSnapshot queue = queue(noShow, token(1, NORMAL, TokenStatus.COMPLETED, 1000));

            assertThat(NextTokenPolicy.recallSortKey(queue, noShow, DEFAULTS)).isEqualTo(2000);
        }

        @Test
        void twoRecallsBehindTheSameTokenAreOrderedByNumber() {
            QueueSnapshot queue = queue(
                    token(7, NORMAL, TokenStatus.WAITING, 3001),
                    token(3, NORMAL),
                    token(2, NORMAL, TokenStatus.WAITING, 3001),
                    token(4, NORMAL));

            assertThat(projectedNumbers(queue, State.INITIAL, DEFAULTS)).containsExactly(3, 2, 7, 4);
        }
    }

    @Nested
    class Validation {

        @Test
        void settingsRejectNegativeCapAndOffsetBelowOne() {
            assertThatIllegalArgumentException().isThrownBy(() -> new Settings(-1, 2));
            assertThatIllegalArgumentException().isThrownBy(() -> new Settings(2, 0));
            assertThat(new Settings(0, 1)).isNotNull();
        }

        @Test
        void stateRejectsNegativeCounter() {
            assertThatIllegalArgumentException().isThrownBy(() -> new State(-1));
        }

        @Test
        void recordsRejectMissingValues() {
            TokenView valid = token(1, NORMAL);
            assertThatNullPointerException().isThrownBy(() -> new Decision(null, State.INITIAL));
            assertThatNullPointerException().isThrownBy(() -> new Decision(valid, null));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TokenView(null, 1, NORMAL, TokenStatus.WAITING, 1000, JOINED));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TokenView(valid.id(), 1, null, TokenStatus.WAITING, 1000, JOINED));
            assertThatNullPointerException().isThrownBy(() -> new TokenView(valid.id(), 1, NORMAL, null, 1000, JOINED));
            assertThatNullPointerException()
                    .isThrownBy(() -> new TokenView(valid.id(), 1, NORMAL, TokenStatus.WAITING, 1000, null));
        }

        @Test
        void snapshotIsACopy() {
            List<TokenView> tokens = new ArrayList<>(List.of(token(1, NORMAL)));
            QueueSnapshot snapshot = new QueueSnapshot(tokens);
            tokens.add(token(2, NORMAL));

            assertThat(snapshot.tokens()).hasSize(1);
        }
    }
}
