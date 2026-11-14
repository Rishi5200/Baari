package com.baari.queue;

import java.util.List;

/**
 * The patient's own token page (PLAN §2.2 screen 2). No name, phone or ids: whoever has the link
 * sees this, so it holds only what the patient needs. ETA arrives in Phase 4.
 *
 * @param position 1 = called next; null unless WAITING
 * @param peopleAhead waiting patients who will be called first; null unless WAITING
 * @param nowServing display codes currently called or in consultation, latest first
 * @param queueStatus the token's queue day status (CLOSED → "Queue closed for today")
 * @param room the room label of the doctor who called this token ("go to Room 2"); null if none
 */
public record PublicTokenView(
        String displayCode,
        TokenStatus status,
        Integer position,
        Integer peopleAhead,
        List<String> nowServing,
        String queueName,
        QueueDayStatus queueStatus,
        String room) {

    public PublicTokenView {
        nowServing = List.copyOf(nowServing);
    }
}
