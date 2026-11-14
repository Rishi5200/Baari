package com.baari.realtime;

import com.baari.queue.QueueChanged;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Carries "queue X changed" between app instances through Redis pub/sub (PLAN §10.2).
 *
 * <p>Every instance publishes after commit and every instance subscribes, including the one that
 * made the change: the instance that handled the click and the instances holding other
 * browsers' connections all take the same path, so there is only one code path to get right.
 * Pub/sub is fire-and-forget, which is fine here because the message only says "rebuild the
 * snapshot"; a lost message is repaired by the next change, the next reconnect or a refresh.
 */
@Configuration
class RedisFanOut {

    private static final Logger log = LoggerFactory.getLogger(RedisFanOut.class);

    /** One channel per queue: {@code baari:queue:{queueId}}; the message is the event sequence number. */
    static final String CHANNEL_PREFIX = "baari:queue:";

    private final StringRedisTemplate redis;
    private final SseHub hub;

    RedisFanOut(StringRedisTemplate redis, SseHub hub) {
        this.redis = redis;
        this.hub = hub;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onQueueChanged(QueueChanged event) {
        try {
            redis.convertAndSend(CHANNEL_PREFIX + event.queueId(), Long.toString(event.seq()));
        } catch (RuntimeException e) {
            // Redis down: still update the browsers connected to this instance. Other instances
            // catch up on their next change.
            log.warn("could not publish queue change to Redis, notifying local connections only: {}", e.toString());
            hub.queueChanged(event.queueId(), event.seq());
        }
    }

    @Bean
    RedisMessageListenerContainer queueChangeListener(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(
                (message, pattern) -> {
                    try {
                        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
                        UUID queueId = UUID.fromString(channel.substring(CHANNEL_PREFIX.length()));
                        long seq = Long.parseLong(new String(message.getBody(), StandardCharsets.UTF_8));
                        hub.queueChanged(queueId, seq);
                    } catch (RuntimeException e) {
                        log.warn("ignoring malformed queue-change message: {}", e.toString());
                    }
                },
                new PatternTopic(CHANNEL_PREFIX + "*"));
        return container;
    }
}
