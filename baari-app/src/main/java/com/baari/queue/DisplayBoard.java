package com.baari.queue;

import java.util.List;

/**
 * What the waiting-room TV shows (PLAN §2.2 screen 5). Display codes only: the screen is public,
 * so no names, phone numbers or ids.
 *
 * @param status today's status, or null if the queue hasn't been opened today
 * @param nowServing called or in-consultation tokens, latest first
 * @param next the next few waiting codes, in call order
 * @param waiting how many patients are waiting
 */
public record DisplayBoard(
        String queueName, QueueDayStatus status, List<Serving> nowServing, List<String> next, int waiting) {

    public DisplayBoard {
        nowServing = List.copyOf(nowServing);
        next = List.copyOf(next);
    }

    /** @param room the doctor's room label, or null */
    public record Serving(String code, String room) {}
}
