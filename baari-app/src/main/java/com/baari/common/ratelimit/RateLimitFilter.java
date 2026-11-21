package com.baari.common.ratelimit;

import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;

/**
 * Per-IP rate limits (PLAN §9.3): joining, the patient's token page and staff login. The buckets
 * live in Redis, so every instance counts against the same limit. Over the limit the request gets
 * HTTP 429 with {@code Retry-After}. The per-phone join limit is a business rule and lives in the
 * queue module instead.
 *
 * <p>Runs before Spring Security, so the login POST is covered, and after the forwarded-header
 * filter, so behind Caddy the address is the patient's and not the proxy's. If Redis is down the
 * request is let through: an outage of the limiter must not take the clinic's queue down with it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 100)
@EnableConfigurationProperties(RateLimitProperties.class)
@ConditionalOnProperty(value = "baari.rate-limit.enabled", havingValue = "true", matchIfMissing = true)
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    /** A limited group of requests: so many per minute per client address. */
    record Rule(String name, HttpMethod method, List<PathPattern> paths, int perMinute) {
        boolean matches(HttpServletRequest request) {
            if (!method.matches(request.getMethod())) {
                return false;
            }
            PathContainer path = PathContainer.parsePath(request.getRequestURI());
            return paths.stream().anyMatch(p -> p.matches(path));
        }

        BucketConfiguration bucket() {
            return BucketConfiguration.builder()
                    .addLimit(limit -> limit.capacity(perMinute).refillGreedy(perMinute, Duration.ofMinutes(1)))
                    .build();
        }
    }

    private final List<Rule> rules;
    private final LettuceConnectionFactory redis;
    private final MeterRegistry meters;
    private volatile ProxyManager<byte[]> buckets;

    RateLimitFilter(RateLimitProperties props, LettuceConnectionFactory redis, MeterRegistry meters) {
        PathPatternParser parser = PathPatternParser.defaultInstance;
        this.rules = List.of(
                new Rule(
                        "join",
                        HttpMethod.POST,
                        List.of(
                                parser.parse("/j/{clinic}/{queue}"),
                                parser.parse("/api/public/queues/{queueId}/tokens")),
                        props.joinPerMinute()),
                new Rule(
                        "token",
                        HttpMethod.GET,
                        List.of(parser.parse("/t/{code}"), parser.parse("/api/public/tokens/{code}/**")),
                        props.tokenPagePerMinute()),
                new Rule("login", HttpMethod.POST, List.of(parser.parse("/login")), props.loginPerMinute()));
        this.redis = redis;
        this.meters = meters;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<Rule> rule = rules.stream().filter(r -> r.matches(request)).findFirst();
        if (rule.isEmpty()) {
            chain.doFilter(request, response);
            return;
        }
        Rule r = rule.get();
        ConsumptionProbe probe;
        try {
            byte[] key = ("baari:rl:" + r.name() + ":" + request.getRemoteAddr()).getBytes(StandardCharsets.UTF_8);
            probe = buckets().builder().build(key, r::bucket).tryConsumeAndReturnRemaining(1);
        } catch (RuntimeException e) {
            log.warn("Rate limiter unavailable, letting the request through: {}", e.toString());
            chain.doFilter(request, response);
            return;
        }
        if (probe.isConsumed()) {
            chain.doFilter(request, response);
            return;
        }
        meters.counter("baari.rate_limited", "rule", r.name()).increment();
        long retryAfter = Math.max(1, (probe.getNanosToWaitForRefill() + 999_999_999L) / 1_000_000_000L);
        response.setStatus(429);
        response.setHeader("Retry-After", Long.toString(retryAfter));
        if (request.getRequestURI().startsWith("/api/")) {
            response.setContentType("application/problem+json");
            response.getWriter()
                    .write("{\"type\":\"https://baari.app/problems/rate-limited\",\"title\":\"Too Many Requests\","
                            + "\"status\":429,\"detail\":\"Too many requests. Please wait " + retryAfter
                            + " seconds and try again.\"}");
        } else {
            response.setContentType("text/plain;charset=UTF-8");
            response.getWriter().write("Too many requests. Please wait " + retryAfter + " seconds and try again.");
        }
    }

    /** Built on first use: the Redis client is only ready once the connection factory has started. */
    private ProxyManager<byte[]> buckets() {
        ProxyManager<byte[]> b = buckets;
        if (b == null) {
            synchronized (this) {
                if (buckets == null) {
                    RedisClient client = (RedisClient) redis.getNativeClient();
                    buckets = Bucket4jLettuce.casBasedBuilder(client)
                            .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(
                                    Duration.ofMinutes(1)))
                            .build();
                }
                b = buckets;
            }
        }
        return b;
    }
}
