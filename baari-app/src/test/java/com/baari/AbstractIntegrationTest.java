package com.baari;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class for *IT tests. The containers are singletons: started once per JVM and shared by
 * every integration test (cheaper than one set per class). Ryuk removes them when the JVM exits.
 * There is deliberately no H2 or in-memory substitute: we rely on Postgres-specific features.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            // Test-only keys (public in the repo), so ITs don't need env vars.
            "baari.privacy.data-key=wjseMCd5C/bpklpkXri3tTXUCqnB82QCqpUO2SA9E8c=",
            "baari.privacy.phone-pepper=KWEThGoIRPWCxTVk52s4rCstHYYutasUJGVb2tig9EM="
        })
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7").withExposedPorts(6379);

    @ServiceConnection
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.0.0");

    static {
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }
}
