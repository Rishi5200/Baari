package com.baari.notification;

import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * SMS is a stub (PLAN §11.4). Indian SMS providers require registering each message template
 * (DLT) before anything can be sent, so this stays a plug-in point: it reaches nobody until a real
 * provider is wired in, and the planner therefore never creates SMS notifications.
 */
@Component
class SmsChannel implements NotificationChannel {

    @Override
    public String name() {
        return "SMS";
    }

    @Override
    public boolean canReach(UUID tokenId) {
        return false;
    }

    @Override
    public void send(UUID tokenId, String text) {
        throw new UnsupportedOperationException("No SMS provider is configured");
    }
}
