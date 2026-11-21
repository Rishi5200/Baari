package com.baari.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.CreatedQueue;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.NewClinic;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueMode;
import com.baari.clinic.RotatingQr;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.MutableClock;
import com.baari.common.NotFoundException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.convention.TestBean;

/** Phase 8 "Done when": rotating QR windows (PLAN §9.3) and the PII retention job (PLAN §9.4). */
class RotatingQrAndRetentionIT extends AbstractIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-10-01T04:30:00Z"); // 10:00 in India
    private static final MutableClock CLOCK = MutableClock.at(NOW);
    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    TokenService tokens;

    @Autowired
    QueueDayService days;

    @Autowired
    QueueQueryService queries;

    @Autowired
    QueueDirectory directory;

    @Autowired
    PiiRetentionJob retention;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    JdbcClient jdbc;

    StaffPrincipal admin;
    UUID rotating;
    String displayKey;
    UUID staticQueue;

    @BeforeEach
    void setUp() {
        CLOCK.set(NOW);
        String slug = "qr-" + UUID.randomUUID().toString().substring(0, 8);
        ClinicView clinic = clinicAdmin.createClinic(
                PLATFORM, new NewClinic(slug, "Clinic", "Asia/Kolkata", "adm-" + slug, "s3cret-pass"));
        admin = new StaffPrincipal(UUID.randomUUID(), "admin", StaffRole.CLINIC_ADMIN, clinic.id(), null);
        UUID doctor =
                doctorAdmin.create(admin, new DoctorDetails("Dr. Q", null)).id();
        CreatedQueue created = queueAdmin.create(
                admin, "tv", new QueueDetails("TV", QueueMode.SINGLE, QrMode.ROTATING, "R", Set.of(doctor)));
        rotating = created.queue().id();
        displayKey = created.displayKey();
        staticQueue = queueAdmin
                .create(
                        admin,
                        "poster",
                        new QueueDetails("Poster", QueueMode.SINGLE, QrMode.STATIC, "P", Set.of(doctor)))
                .queue()
                .id();
        days.open(admin, rotating);
        days.open(admin, staticQueue);
    }

    private JoinRequest join(String name, String phone, Long window, String signature) {
        return new JoinRequest(name, phone, VisitType.NEW, "v1", UUID.randomUUID(), window, signature);
    }

    private long currentWindow() {
        return RotatingQr.window(CLOCK.instant());
    }

    private String sig(long window) {
        return directory.qrSignature(rotating, window).orElseThrow();
    }

    // --- rotating QR ---

    @Test
    void theCurrentAndThePreviousWindowAreAccepted() {
        long now = currentWindow();
        assertThat(tokens.join(rotating, join("A", null, now, sig(now))).created())
                .isTrue();
        assertThat(tokens.join(rotating, join("B", null, now - 1, sig(now - 1))).created())
                .isTrue();
    }

    @Test
    void anOldWindowAMissingCodeOrAForgedSignatureIsRejected() {
        long now = currentWindow();
        assertThatThrownBy(() -> tokens.join(rotating, join("Old photo", null, now - 2, sig(now - 2))))
                .isInstanceOf(QrExpiredException.class);
        assertThatThrownBy(() -> tokens.join(rotating, join("No code", null, null, null)))
                .isInstanceOf(QrExpiredException.class);
        assertThatThrownBy(() -> tokens.join(rotating, join("Forged", null, now, "AAAAAAAAAAAAAAAAAAAAAA")))
                .isInstanceOf(QrExpiredException.class);
        // a code from the future is refused too: windows are only ever issued for now
        assertThatThrownBy(() -> tokens.join(rotating, join("Future", null, now + 1, sig(now + 1))))
                .isInstanceOf(QrExpiredException.class);
        assertThat(queries.publicQueue(rotatingClinicSlug(), "tv").waiting()).isZero();
    }

    @Test
    void aRetryOfAnAcceptedJoinStillWorksAfterTheCodeChanged() {
        long now = currentWindow();
        UUID key = UUID.randomUUID();
        IssuedToken first = tokens.join(rotating, new JoinRequest("A", null, VisitType.NEW, "v1", key, now, sig(now)));

        CLOCK.advance(Duration.ofMinutes(5)); // the code the patient scanned is long gone
        IssuedToken retry = tokens.join(rotating, new JoinRequest("A", null, VisitType.NEW, "v1", key, now, sig(now)));

        assertThat(retry.tokenId()).isEqualTo(first.tokenId());
        assertThat(retry.created()).isFalse();
    }

    @Test
    void staticQueuesNeedNoCodeAndTheTvGetsAFreshLinkOnlyForRotatingQueues() {
        assertThat(tokens.join(staticQueue, join("Poster", null, null, null)).created())
                .isTrue();

        DisplayJoinLink link = queries.displayJoinLink(rotating, displayKey).orElseThrow();
        assertThat(link.window()).isEqualTo(currentWindow());
        assertThat(link.path()).contains("/tv?t=" + currentWindow() + "&sig=" + sig(currentWindow()));
        assertThat(link.validUntil()).isAfter(CLOCK.instant());
        assertThatThrownBy(() -> queries.displayJoinLink(rotating, "wrong-key")).isInstanceOf(NotFoundException.class);
    }

    private String rotatingClinicSlug() {
        return jdbc.sql("SELECT c.slug FROM clinic c JOIN service_queue q ON q.clinic_id = c.id WHERE q.id = ?")
                .param(rotating)
                .query(String.class)
                .single();
    }

    // --- retention ---

    private Map<String, Object> row(UUID tokenId) {
        return jdbc.sql("""
                        SELECT patient_name, phone_enc, phone_hash, phone_last4, pii_purged_at, joined_at, status
                          FROM token WHERE id = ?
                        """).param(tokenId).query().singleRow();
    }

    @Test
    void tokensPastTheRetentionPeriodLoseTheirPersonalDataButKeepTheirTimings() {
        IssuedToken old = tokens.join(staticQueue, join("Asha Verma", "+919876543210", null, null));
        jdbc.sql("INSERT INTO patient_channel (token_id, telegram_chat_id_enc, linked_at) VALUES (?, ?, ?)")
                .params(old.tokenId(), new byte[] {1, 2, 3}, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC))
                .update();
        Object joinedBefore = row(old.tokenId()).get("joined_at");

        CLOCK.advance(Duration.ofDays(31));
        days.open(admin, staticQueue);
        IssuedToken recent = tokens.join(staticQueue, join("Ravi Kumar", "+919812345678", null, null));

        int purged = retention.purge();

        assertThat(purged).isGreaterThanOrEqualTo(1);
        Map<String, Object> o = row(old.tokenId());
        assertThat(o.get("patient_name")).isNull();
        assertThat(o.get("phone_enc")).isNull();
        assertThat(o.get("phone_hash")).isNull();
        assertThat(o.get("phone_last4")).isNull();
        assertThat(o.get("pii_purged_at")).isNotNull();
        assertThat(o.get("joined_at")).as("timings stay for the model").isEqualTo(joinedBefore);
        assertThat(o.get("status")).isNotNull();
        assertThat(jdbc.sql("SELECT count(*) FROM patient_channel WHERE token_id = ?")
                        .param(old.tokenId())
                        .query(Integer.class)
                        .single())
                .as("the Telegram link goes with the personal data")
                .isZero();

        Map<String, Object> r = row(recent.tokenId());
        assertThat(r.get("patient_name")).isEqualTo("Ravi Kumar");
        assertThat(r.get("phone_enc")).isNotNull();
        assertThat(r.get("pii_purged_at")).isNull();

        assertThat(retention.purge()).as("a second run finds nothing more").isZero();
    }
}
