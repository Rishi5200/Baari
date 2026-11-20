package com.baari.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code baari.notifications} (PLAN §14).
 *
 * @param notifyAheadCount a patient is told to come back when this many or fewer are ahead of them,
 *     counting themselves (position 3 or better with the default)
 * @param logEnabled the dev and test channel that only writes the message to the log
 * @param maxAttempts after this many failed sends a notification is marked FAILED
 */
@ConfigurationProperties("baari.notifications")
record NotificationProperties(int notifyAheadCount, boolean logEnabled, int maxAttempts) {

    NotificationProperties {
        if (notifyAheadCount < 1) {
            notifyAheadCount = 3;
        }
        if (maxAttempts < 1) {
            maxAttempts = 5;
        }
    }
}
