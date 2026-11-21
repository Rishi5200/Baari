package com.baari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApplicationSmokeIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @LocalManagementPort
    int managementPort;

    @Autowired
    JdbcClient jdbc;

    private static HttpResponse<String> get(int port, String path) throws Exception {
        return HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void healthAndMetricsAreOnTheManagementPortOnly() throws Exception {
        assertThat(managementPort).isNotEqualTo(port);
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"");
        assertThat(get(managementPort, "/actuator/prometheus").statusCode()).isEqualTo(200);

        // the public port (the only one Caddy forwards) serves no actuator endpoint (PLAN §9.5)
        assertThat(get(port, "/actuator/prometheus").statusCode()).isIn(401, 302, 404);
        assertThat(get(port, "/actuator/prometheus").body()).doesNotContain("jvm_");
    }

    @Test
    void pagesCarryTheSecurityHeaders() throws Exception {
        HttpResponse<String> login = get(port, "/login");
        String csp = login.headers().firstValue("Content-Security-Policy").orElseThrow();
        assertThat(csp).contains("default-src 'self'", "frame-ancestors 'none'", "script-src 'self' 'nonce-");
        assertThat(login.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(login.headers().firstValue("X-Frame-Options")).hasValue("DENY");
        assertThat(login.headers().firstValue("Referrer-Policy")).hasValue("strict-origin-when-cross-origin");
        // a fresh nonce for every response
        String other = get(port, "/login")
                .headers()
                .firstValue("Content-Security-Policy")
                .orElseThrow();
        assertThat(other).isNotEqualTo(csp);
    }

    @Test
    void flywayAppliedV1AndClinicTableExists() {
        Integer applied = jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE version = '1' AND success")
                .query(Integer.class)
                .single();
        Integer clinicTables = jdbc.sql("SELECT count(*) FROM information_schema.tables WHERE table_name = 'clinic'")
                .query(Integer.class)
                .single();

        assertThat(applied).isEqualTo(1);
        assertThat(clinicTables).isEqualTo(1);
    }
}
