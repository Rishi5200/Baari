package com.baari;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;

class ApplicationSmokeIT extends AbstractIntegrationTest {

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Test
    void healthEndpointReportsUp() throws Exception {
        HttpResponse<String> response = HttpClient.newHttpClient()
                .send(
                        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
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
