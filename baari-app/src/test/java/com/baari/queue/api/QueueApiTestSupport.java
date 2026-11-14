package com.baari.queue.api;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
import com.baari.common.MutableClock;
import com.baari.identity.internal.StaffUserDetails;
import com.jayway.jsonpath.JsonPath;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Shared setup for the queue API tests: a fresh clinic with two doctors, a SINGLE queue
 * {@code dr-sharma} (prefix A, doctor 1) and a POOL queue {@code general} (prefix B, both doctors),
 * principals for each role, and small HTTP helpers. Requests go through the real security filter
 * chain (session principal, CSRF), so these are true API-level tests.
 */
@AutoConfigureMockMvc
abstract class QueueApiTestSupport extends AbstractIntegrationTest {

    static final Instant NOW = Instant.parse("2026-11-14T04:30:00Z"); // 10:00 in India
    static final MutableClock CLOCK = MutableClock.at(NOW);
    static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    JsonMapper json;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    ClinicView clinic;
    StaffPrincipal admin;
    StaffPrincipal receptionist;
    StaffPrincipal doctor1;
    StaffPrincipal doctor2;
    UUID single;
    UUID pool;

    static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    ClinicView newClinic() {
        return clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic(unique("clinic"), "Sharma Clinic", "Asia/Kolkata", unique("admin"), "s3cret-pass"));
    }

    @BeforeEach
    void setUpClinic() {
        CLOCK.set(NOW);
        clinic = newClinic();
        admin = new StaffPrincipal(UUID.randomUUID(), unique("admin"), StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist = new StaffPrincipal(UUID.randomUUID(), unique("rec"), StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID d1 = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Rajesh Sharma", "Room 1"))
                .id();
        UUID d2 = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Neha Gill", "Room 2"))
                .id();
        doctor1 = new StaffPrincipal(UUID.randomUUID(), unique("d1"), StaffRole.DOCTOR, clinic.id(), d1);
        doctor2 = new StaffPrincipal(UUID.randomUUID(), unique("d2"), StaffRole.DOCTOR, clinic.id(), d2);
        single = queueAdmin
                .create(
                        admin,
                        "dr-sharma",
                        new QueueDetails("Dr. Sharma", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(d1)))
                .queue()
                .id();
        pool = queueAdmin
                .create(
                        admin,
                        "general",
                        new QueueDetails("General OPD", QueueMode.POOL, QrMode.STATIC, "B", Set.of(d1, d2)))
                .queue()
                .id();
    }

    // --- HTTP helpers ---

    /** Logs the request in as this principal, exactly as the session would after a form login. */
    static RequestPostProcessor as(StaffPrincipal p) {
        return user(new StaffUserDetails(p, null, true, false));
    }

    ResultActions staffPost(StaffPrincipal p, String url, Object body) throws Exception {
        MockHttpServletRequestBuilder request =
                MockMvcRequestBuilders.post(url).with(as(p)).with(csrf());
        return mvc.perform(withBody(request, body));
    }

    ResultActions staffPost(StaffPrincipal p, String url) throws Exception {
        return staffPost(p, url, null);
    }

    ResultActions staffGet(StaffPrincipal p, String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url).with(as(p)));
    }

    /** Patients: no login, no CSRF token (none exists for them). */
    ResultActions publicPost(String url, Object body) throws Exception {
        return mvc.perform(withBody(MockMvcRequestBuilders.post(url), body));
    }

    ResultActions publicGet(String url) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(url));
    }

    private MockHttpServletRequestBuilder withBody(MockHttpServletRequestBuilder request, Object body) {
        if (body == null) {
            return request;
        }
        return request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    }

    static <T> T read(ResultActions result, String path) throws Exception {
        return JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
    }

    // --- request bodies ---

    static Map<String, Object> joinBody(String name, UUID key) {
        return Map.of("name", name, "visitType", "NEW", "consentVersion", "v1", "idempotencyKey", key.toString());
    }

    static Map<String, Object> walkInBody(String name, String priority) {
        return Map.of("name", name, "visitType", "NEW", "priority", priority);
    }

    // --- shortcuts for common steps ---

    void openDay(UUID queueId) throws Exception {
        staffPost(receptionist, "/api/staff/queues/" + queueId + "/days/today/open");
    }

    /** Adds a walk-in through the API and returns its token id. */
    String walkIn(UUID queueId, String name, String priority) throws Exception {
        return read(
                staffPost(receptionist, "/api/staff/queues/" + queueId + "/tokens", walkInBody(name, priority)),
                "$.tokenId");
    }
}
