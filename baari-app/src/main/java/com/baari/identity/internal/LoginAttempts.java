package com.baari.identity.internal;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Locks a username for 15 minutes after 5 failed logins (PLAN §9.1). State lives in Redis so the
 * lock holds across app instances and restarts.
 *
 * <p>Two keys per username:
 *
 * <ul>
 *   <li>{@code login:fail:<user>}: failure counter. {@code INCR} is atomic, so concurrent wrong
 *       guesses can't slip past the limit. Its Redis TTL (15 min) only cleans up old counts.
 *   <li>{@code login:lock:<user>}: the instant the lock ends. Whether the lock is still active is
 *       decided with the injected {@link Clock}, not by Redis expiry, so tests can move time
 *       forward. Redis TTL on this key is again only cleanup.
 * </ul>
 *
 * Unknown usernames are counted too: the lock behaves the same whether or not the user exists.
 */
@Component
class LoginAttempts {

    static final int MAX_FAILURES = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private static final Logger log = LoggerFactory.getLogger(LoginAttempts.class);

    private final StringRedisTemplate redis;
    private final Clock clock;

    LoginAttempts(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    boolean isLocked(String username) {
        String lockedUntil = redis.opsForValue().get(lockKey(username));
        return lockedUntil != null
                && Instant.ofEpochMilli(Long.parseLong(lockedUntil)).isAfter(clock.instant());
    }

    void recordFailure(String username) {
        String failKey = failKey(username);
        Long failures = redis.opsForValue().increment(failKey);
        if (failures != null && failures == 1) {
            redis.expire(failKey, LOCK_DURATION);
        }
        if (failures != null && failures >= MAX_FAILURES) {
            Instant until = clock.instant().plus(LOCK_DURATION);
            redis.opsForValue().set(lockKey(username), Long.toString(until.toEpochMilli()), LOCK_DURATION);
            // Start from zero after the lock ends, instead of locking again on the next mistake.
            redis.delete(failKey);
            log.info("Staff login locked for {} after {} failed attempts", username, failures);
        }
    }

    void recordSuccess(String username) {
        redis.delete(failKey(username));
    }

    private static String failKey(String username) {
        return "login:fail:" + username;
    }

    private static String lockKey(String username) {
        return "login:lock:" + username;
    }
}
