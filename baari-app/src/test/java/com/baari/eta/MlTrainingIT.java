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
import com.baari.common.ForbiddenException;
import com.baari.common.MutableClock;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

/**
 * Phase 7 end to end against Postgres: training on stored history, the 5% promotion rule, the
 * champion used for live estimates, and the fallback when a stored model cannot be read.
 */
class MlTrainingIT extends AbstractIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Kolkata");
    private static final Instant NOW = Instant.parse("2026-11-20T06:00:00Z"); // 11:30 in India
    private static final MutableClock CLOCK = MutableClock.at(NOW);
    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    MlTraining training;

    @Autowired
    MlModelStore store;

    @Autowired
    EtaService eta;

    @Autowired
    AccuracyReport accuracy;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    StaffPrincipal admin;
    UUID clinicId;
    UUID doctorId;
    UUID queueId;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        String slug = "ml-" + UUID.randomUUID().toString().substring(0, 8);
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM, new NewClinic(slug, "Clinic", "Asia/Kolkata", "adm-" + slug, "s3cret-pass"));
        clinicId = clinic.id();
        admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinicId, null);
        doctorId = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Slowing", null))
                .id();
        queueId = queueAdmin
                .create(admin, "dr", new QueueDetails("Dr", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(doctorId)))
                .queue()
                .id();
    }

    /**
     * {@code days} past days, 30 patients a day from 09:00. Each consultation is longer than the one
     * before it (2 min for the first, about 14 for the 30th), with 10% noise. Hour-of-day averages
     * blur that within each hour; the session-position feature does not.
     */
    private void history(int days) {
        Random r = new Random(7);
        LocalDate today = LocalDate.ofInstant(NOW, ZONE);
        for (int d = days; d >= 1; d--) {
            LocalDate date = today.minusDays(d);
            UUID day = UUID.randomUUID();
            Instant opens = date.atTime(8, 50).atZone(ZONE).toInstant();
            Instant free = date.atTime(9, 0).atZone(ZONE).toInstant();
            Instant lastEnd = free;
            for (int i = 0; i < 30; i++) {
                Instant joined = opens.plusSeconds(60L * i);
                Instant called = free.isBefore(joined) ? joined : free;
                Instant started = called.plusSeconds(30);
                long seconds = Math.round((120 + 25 * i) * (1 + 0.1 * r.nextGaussian()));
                Instant completed = started.plusSeconds(Math.max(30, seconds));
                if (i == 0) {
                    jdbc.sql("""
                                    INSERT INTO queue_day (id, queue_id, clinic_id, service_date, status, last_number,
                                                           opened_at, closed_at)
                                    VALUES (?, ?, ?, ?, 'CLOSED', 30, ?, ?)
                                    """)
                            .params(day, queueId, clinicId, date, odt(opens), odt(opens.plusSeconds(6 * 3600)))
                            .update();
                }
                jdbc.sql("""
                                INSERT INTO token (id, queue_day_id, clinic_id, number, sort_key, public_code, visit_type,
                                                   priority, status, source, joined_at, called_at, consult_started_at,
                                                   completed_at, served_by_doctor_id)
                                VALUES (?, ?, ?, ?, ?, ?, 'NEW', 'NORMAL', 'COMPLETED', 'QR', ?, ?, ?, ?, ?)
                                """)
                        .params(
                                UUID.randomUUID(),
                                day,
                                clinicId,
                                i + 1,
                                (i + 1) * 1000L,
                                UUID.randomUUID().toString().replace("-", "").substring(0, 22),
                                odt(joined),
                                odt(called),
                                odt(started),
                                odt(completed),
                                doctorId)
                        .update();
                free = completed;
                lastEnd = completed;
            }
            assertThat(lastEnd).isAfter(free.minusSeconds(1));
        }
    }

    private static OffsetDateTime odt(Instant i) {
        return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
    }

    private int models(String status) {
        return jdbc.sql("SELECT count(*) FROM ml_model WHERE clinic_id = ? AND status = ?")
                .params(clinicId, status)
                .query(Integer.class)
                .single();
    }

    @Test
    void aModelThatClearlyBeatsRollingIsPromotedAndUsedForLiveEstimates() {
        history(60);

        MlTraining.Result result = training.trainNow(admin);

        assertThat(result.outcome()).isEqualTo(MlTraining.Outcome.PROMOTED);
        assertThat(result.baseline()).isEqualTo("ROLLING");
        assertThat(result.bestWaitMaeMin()).isLessThan(result.baselineWaitMaeMin() * 0.95);
        assertThat(models("CHAMPION")).isEqualTo(1);
        assertThat(models("REJECTED")).isEqualTo(1);
        assertThat(eta.estimatorFor(clinicId, ZONE, NOW).name())
                .startsWith("ML:")
                .endsWith(":v" + versionOfChampion());

        // the accuracy page lists both models with their metrics
        AccuracyReport.View view = accuracy.view(admin);
        assertThat(view.models()).hasSize(2);
        assertThat(view.models()).anySatisfy(m -> {
            assertThat(m.status()).isEqualTo("CHAMPION");
            assertThat(m.waitMaeMinutes()).isNotNull();
            assertThat(m.baseline()).isEqualTo("ROLLING");
        });
    }

    @Test
    void aSecondRunMustBeatTheChampionNotRollingAndThereIsStillOnlyOneChampion() {
        history(60);
        training.trainNow(admin);
        UUID first = store.championRef(clinicId).orElseThrow().id();

        MlTraining.Result again = training.trainNow(admin);

        assertThat(again.baseline()).startsWith("ML v");
        // the same data trains the same models, which cannot beat themselves by 5%
        assertThat(again.outcome()).isEqualTo(MlTraining.Outcome.KEPT);
        assertThat(store.championRef(clinicId).orElseThrow().id()).isEqualTo(first);
        assertThat(models("CHAMPION")).isEqualTo(1);
        assertThat(models("REJECTED")).isEqualTo(3);
    }

    @Test
    void theChampionIsLoadedFromTheDatabaseAsAfterARestart() {
        history(60);
        training.trainNow(admin);

        MlModelStore.ChampionRef ref = store.championRef(clinicId).orElseThrow();
        MlModelStore.Champion loaded = store.load(ref).orElseThrow();
        ServiceContext late = new ServiceContext(
                doctorId,
                com.baari.queue.VisitType.NEW,
                11,
                com.baari.queue.Priority.NORMAL,
                java.time.DayOfWeek.MONDAY,
                25,
                175, // as on a real day: the 25th patient goes in nearly 3 hours into the session
                5);
        assertThat(loaded.model().predict(late).meanS())
                .as("25th patient: about 12 min")
                .isBetween(600.0, 900.0);
    }

    @Test
    void aCorruptStoredModelFallsBackToRollingWithAnAlert() {
        history(60);
        training.trainNow(admin);
        jdbc.sql("UPDATE ml_model SET artifact = ? WHERE clinic_id = ? AND status = 'CHAMPION'")
                .params(new byte[] {1, 2, 3}, clinicId)
                .update();
        double failuresBefore = meters.counter("baari.eta.ml.load_failures").count();

        ServiceTimeEstimator estimator = eta.estimatorFor(clinicId, ZONE, NOW);

        assertThat(estimator.name()).isEqualTo(RollingStatsEstimator.NAME);
        assertThat(meters.counter("baari.eta.ml.load_failures").count()).isEqualTo(failuresBefore + 1);
    }

    @Test
    void tooLittleHistoryTrainsNothing() {
        history(15); // 7 test days leave 8 training days: 240 consultations, under 300

        MlTraining.Result result = training.trainNow(admin);

        assertThat(result.outcome()).isEqualTo(MlTraining.Outcome.TOO_FEW_SAMPLES);
        assertThat(models("CHAMPION") + models("REJECTED")).isZero();
    }

    @Test
    void onlyTheClinicAdminMayTrain() {
        StaffPrincipal receptionist =
                new StaffPrincipal(UUID.randomUUID(), "rec", StaffRole.RECEPTIONIST, clinicId, null);
        assertThatThrownBy(() -> training.trainNow(receptionist)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> accuracy.view(receptionist)).isInstanceOf(ForbiddenException.class);
    }

    private int versionOfChampion() {
        return store.championRef(clinicId).orElseThrow().version();
    }
}
