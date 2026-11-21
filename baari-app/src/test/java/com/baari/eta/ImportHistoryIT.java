package com.baari.eta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.NewClinic;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs {@code sim/import-history.sql}, the same file the psql wrapper uses, against real Postgres.
 * The CSV is replaced by plain inserts into the temporary table the wrapper would fill with
 * {@code \copy}. Dates are relative to the real today, because the script compares with the
 * database's own clock.
 */
class ImportHistoryIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    DataSource dataSource;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    EtaService eta;

    String clinicSlug;
    UUID clinicId;
    UUID singleQueue;
    UUID poolQueue;

    @BeforeEach
    void setUp() {
        clinicSlug = "import-" + UUID.randomUUID().toString().substring(0, 8);
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM, new NewClinic(clinicSlug, "Clinic", "Asia/Kolkata", "adm-" + clinicSlug, "s3cret-pass"));
        clinicId = clinic.id();
        StaffPrincipal admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinicId, null);
        UUID a = doctorAdmin.create(admin, new DoctorDetails("Dr. A", null)).id();
        UUID b = doctorAdmin.create(admin, new DoctorDetails("Dr. B", null)).id();
        singleQueue = queueAdmin
                .create(admin, "dr-a", new QueueDetails("Dr. A", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(a)))
                .queue()
                .id();
        poolQueue = queueAdmin
                .create(admin, "general", new QueueDetails("General", QueueMode.POOL, QrMode.STATIC, "B", Set.of(a, b)))
                .queue()
                .id();
    }

    /** One row as the simulator writes it; {@code minutes} after 10:00 clinic time on {@code date}. */
    private static String row(LocalDate date, String doctor, int number, String status, int minutes) {
        ZonedDateTime joined = date.atTime(10, 0).atZone(ZONE).plusMinutes(minutes);
        String called = "'" + joined.plusMinutes(20).toOffsetDateTime() + "'";
        String started =
                status.equals("COMPLETED") ? "'" + joined.plusMinutes(21).toOffsetDateTime() + "'" : "NULL";
        String done = status.equals("COMPLETED") ? "'" + joined.plusMinutes(28).toOffsetDateTime() + "'" : "NULL";
        return "('%s', '%s', %d, 'NEW', 'NORMAL', 'QR', '%s', '%s', %s, %s, %s)"
                .formatted(date, doctor, number, status, joined.toOffsetDateTime(), called, started, done);
    }

    private void runImport(String values) {
        String script;
        try {
            script = Files.readString(Path.of("..", "sim", "import-history.sql"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        tx.executeWithoutResult(status -> {
            var connection = DataSourceUtils.getConnection(dataSource);
            try (Statement st = connection.createStatement()) {
                st.execute("CREATE TEMP TABLE sim_settings (clinic_slug text NOT NULL) ON COMMIT DROP");
                st.execute("INSERT INTO sim_settings VALUES ('" + clinicSlug + "')");
                st.execute("""
                        CREATE TEMP TABLE sim_token (
                          service_date date, doctor text, number int, visit_type text, priority text, source text,
                          status text, joined_at timestamptz, called_at timestamptz,
                          consult_started_at timestamptz, completed_at timestamptz) ON COMMIT DROP
                        """);
                st.execute("INSERT INTO sim_token VALUES " + values);
                // the driver sends the whole file as one simple query, so DO $$ ... $$ blocks stay intact
                st.execute(script);
            } catch (java.sql.SQLException e) {
                throw new IllegalStateException(e.getMessage(), e);
            }
        });
    }

    private int count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Integer.class).single();
    }

    @Test
    void loadsPastDaysAsClosedQueueDaysWithTheirTokens() {
        LocalDate today = LocalDate.now(ZONE);
        LocalDate d1 = today.minusDays(2);
        LocalDate d2 = today.minusDays(1);
        runImport(String.join(
                ",",
                row(d1, "Dr. A", 1, "COMPLETED", 0),
                row(d1, "Dr. A", 2, "NO_SHOW", 5),
                row(d1, "Dr. B", 1, "COMPLETED", 0),
                row(d2, "Dr. A", 1, "COMPLETED", 0),
                row(today, "Dr. A", 1, "COMPLETED", 0))); // today is never loaded

        assertThat(count("SELECT count(*) FROM queue_day WHERE queue_id = ? AND status = 'CLOSED'", singleQueue))
                .isEqualTo(2);
        // Dr. B serves only the pool queue, so that is where their patients go
        assertThat(count("SELECT count(*) FROM queue_day WHERE queue_id = ?", poolQueue))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM token WHERE clinic_id = ?", clinicId))
                .isEqualTo(4);
        assertThat(count(
                        "SELECT count(*) FROM token WHERE clinic_id = ? AND status = 'COMPLETED' AND served_by_doctor_id IS NOT NULL",
                        clinicId))
                .isEqualTo(3);
        assertThat(count("SELECT last_number FROM queue_day WHERE queue_id = ? AND service_date = ?", singleQueue, d1))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM token WHERE clinic_id = ? AND patient_name IS NOT NULL", clinicId))
                .isZero();
    }

    @Test
    void rerunningSkipsDaysThatAreAlreadyThere() {
        LocalDate d1 = LocalDate.now(ZONE).minusDays(3);
        String rows = row(d1, "Dr. A", 1, "COMPLETED", 0);
        runImport(rows);
        runImport(rows);
        assertThat(count("SELECT count(*) FROM token WHERE clinic_id = ?", clinicId))
                .isEqualTo(1);
    }

    @Test
    void anUnknownDoctorStopsTheWholeImport() {
        LocalDate d1 = LocalDate.now(ZONE).minusDays(1);
        assertThatThrownBy(() -> runImport(String.join(
                        ",", row(d1, "Dr. A", 1, "COMPLETED", 0), row(d1, "Dr. Nobody", 1, "COMPLETED", 0))))
                .hasMessageContaining("Dr. Nobody");
        assertThat(count("SELECT count(*) FROM token WHERE clinic_id = ?", clinicId))
                .isZero();
    }

    @Test
    void importedHistoryIsEnoughForTheRollingEstimator() {
        LocalDate d1 = LocalDate.now(ZONE).minusDays(1);
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 25; i++) {
            rows.append(i > 1 ? "," : "").append(row(d1, "Dr. A", i, "COMPLETED", i * 8));
        }
        runImport(rows.toString());
        assertThat(eta.estimatorFor(clinicId, ZONE, Instant.now()).name()).isEqualTo(RollingStatsEstimator.NAME);
    }
}
