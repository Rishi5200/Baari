package com.baari.notification;

import com.baari.queue.PiiPurged;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Deletes the Telegram links of tokens whose personal data was purged (PLAN §9.4). Runs inside the
 * purge's transaction (a plain synchronous listener), so either both go or neither does.
 */
@Component
class PatientChannelRetention {

    private final JdbcClient jdbc;

    PatientChannelRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener
    void on(PiiPurged event) {
        jdbc.sql("DELETE FROM patient_channel WHERE token_id IN (:ids)")
                .param("ids", event.tokenIds())
                .update();
    }
}
