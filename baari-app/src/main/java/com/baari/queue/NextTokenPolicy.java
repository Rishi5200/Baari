package com.baari.queue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The single source of truth for who is called next (PLAN §5). Call-next uses {@link #next}; the
 * ETA projection uses {@link #projectOrder}, which is built on {@code next}, so the order a
 * patient is shown is the order they will really be called in.
 *
 * <p>Pure: no clock, no database, no Spring.
 */
public final class NextTokenPolicy {

    /**
     * Line order within one priority. Two recalls placed after the same token share a sort key
     * ({@code key + 1}), so the token number breaks the tie and the order stays deterministic.
     */
    private static final Comparator<TokenView> LINE_ORDER =
            Comparator.comparingLong(TokenView::sortKey).thenComparingInt(TokenView::number);

    private NextTokenPolicy() {}

    /**
     * @param maxConsecutivePriority PRIORITY tokens called in a row before a waiting NORMAL token
     *     gets its turn
     * @param recallInsertOffset how many NORMAL tokens a recalled no-show goes behind
     */
    public record Settings(int maxConsecutivePriority, int recallInsertOffset) {
        public Settings {
            if (maxConsecutivePriority < 0) {
                throw new IllegalArgumentException("maxConsecutivePriority must be >= 0");
            }
            if (recallInsertOffset < 1) {
                throw new IllegalArgumentException("recallInsertOffset must be >= 1");
            }
        }
    }

    /** What the policy remembers between calls; persisted on {@code queue_day}. */
    public record State(int consecutivePriorityServed) {
        public static final State INITIAL = new State(0);

        public State {
            if (consecutivePriorityServed < 0) {
                throw new IllegalArgumentException("consecutivePriorityServed must be >= 0");
            }
        }
    }

    /** The token to call, and the policy state to store once the call is made. */
    public record Decision(TokenView token, State stateAfter) {
        public Decision {
            Objects.requireNonNull(token, "token");
            Objects.requireNonNull(stateAfter, "stateAfter");
        }
    }

    /** The next WAITING token to call, or empty if nobody is waiting. */
    public static Optional<Decision> next(QueueSnapshot snapshot, State state, Settings settings) {
        List<TokenView> waiting = waiting(snapshot);

        // An emergency doesn't change the PRIORITY/NORMAL balance, so the counter is left alone.
        Optional<TokenView> emergency = firstInLine(waiting, Priority.EMERGENCY);
        if (emergency.isPresent()) {
            return Optional.of(new Decision(emergency.get(), state));
        }

        Optional<TokenView> priority = firstInLine(waiting, Priority.PRIORITY);
        Optional<TokenView> normal = firstInLine(waiting, Priority.NORMAL);
        boolean priorityTurn =
                normal.isEmpty() || state.consecutivePriorityServed() < settings.maxConsecutivePriority();
        if (priority.isPresent() && priorityTurn) {
            return Optional.of(new Decision(priority.get(), new State(state.consecutivePriorityServed() + 1)));
        }
        return normal.map(token -> new Decision(token, State.INITIAL));
    }

    /**
     * Every WAITING token in the order it will be called, by applying {@link #next} again and
     * again to a copy. A token's position is its index in this list.
     */
    public static List<TokenView> projectOrder(QueueSnapshot snapshot, State state, Settings settings) {
        List<TokenView> remaining = new ArrayList<>(waiting(snapshot));
        List<TokenView> order = new ArrayList<>(remaining.size());
        State current = state;
        Optional<Decision> decision = next(new QueueSnapshot(remaining), current, settings);
        while (decision.isPresent()) {
            order.add(decision.get().token());
            remaining.remove(decision.get().token());
            current = decision.get().stateAfter();
            decision = next(new QueueSnapshot(remaining), current, settings);
        }
        return order;
    }

    /**
     * The sort key for a recalled no-show: just behind the {@code recallInsertOffset}-th waiting
     * NORMAL token, or at the end of the line if fewer are waiting. The +1 fits inside the
     * 1000-wide gaps, so no other token needs renumbering.
     *
     * @param recalled the NO_SHOW token being recalled; its own key is kept if nobody is waiting
     */
    public static long recallSortKey(QueueSnapshot snapshot, TokenView recalled, Settings settings) {
        List<TokenView> waiting = waiting(snapshot);
        List<TokenView> normals = waiting.stream()
                .filter(t -> t.priority() == Priority.NORMAL)
                .sorted(LINE_ORDER)
                .toList();
        if (normals.size() >= settings.recallInsertOffset()) {
            return normals.get(settings.recallInsertOffset() - 1).sortKey() + 1;
        }
        long lastKey = waiting.stream().mapToLong(TokenView::sortKey).max().orElse(recalled.sortKey() - 1);
        return lastKey + 1;
    }

    private static List<TokenView> waiting(QueueSnapshot snapshot) {
        return snapshot.tokens().stream()
                .filter(t -> t.status() == TokenStatus.WAITING)
                .toList();
    }

    private static Optional<TokenView> firstInLine(List<TokenView> waiting, Priority priority) {
        return waiting.stream().filter(t -> t.priority() == priority).min(LINE_ORDER);
    }
}
