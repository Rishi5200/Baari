package com.baari.queue;

import java.util.List;

/**
 * Today's queue as the staff console shows it (PLAN §2.2 screen 4).
 *
 * @param day null if today's day hasn't been opened (all lists are then empty)
 * @param waiting in the order call-next will take them; the first is called next
 * @param finished completed, cancelled and expired tokens, by number
 */
public record Board(
        QueueDayView day,
        List<StaffTokenView> waiting,
        List<StaffTokenView> called,
        List<StaffTokenView> inConsultation,
        List<StaffTokenView> noShow,
        List<StaffTokenView> finished) {

    public Board {
        waiting = List.copyOf(waiting);
        called = List.copyOf(called);
        inConsultation = List.copyOf(inConsultation);
        noShow = List.copyOf(noShow);
        finished = List.copyOf(finished);
    }
}
