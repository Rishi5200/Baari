package com.baari.notification;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dev and test channel: writes the message to the log. The text holds only the token code and
 * counts, never a name or phone number, so it is safe to log.
 */
@Component
class LogChannel implements NotificationChannel {

    private static final Logger log = LoggerFactory.getLogger(LogChannel.class);

    private final NotificationProperties props;

    LogChannel(NotificationProperties props) {
        this.props = props;
    }

    @Override
    public String name() {
        return "LOG";
    }

    @Override
    public boolean canReach(UUID tokenId) {
        return props.logEnabled();
    }

    @Override
    public void send(UUID tokenId, String text) {
        log.info("Notification for token {}: {}", tokenId, text);
    }
}
