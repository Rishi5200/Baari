package com.baari.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.identity.internal.StaffUserDetails;
import com.baari.queue.Priority;
import com.baari.queue.QueueDayService;
import com.baari.queue.QueueQueryService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.VisitType;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The Phase 3 pages through the real security filter chain: the patient join and token pages (no
 * login, no session), the staff console shell, the TV page and the poster print view.
 */
@AutoConfigureMockMvc
class LivePagesIT extends AbstractIntegrationTest {

    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @Autowired
    MockMvc mvc;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    QueueDayService days;

    @Autowired
    TokenService tokens;

    @Autowired
    TokenActionService actions;

    @Autowired
    QueueQueryService queries;

    ClinicView clinic;
    StaffPrincipal admin;
    StaffPrincipal receptionist;
    StaffPrincipal doctor;
    StaffPrincipal otherDoctor;
    UUID queueId;
    String queueSlug = "general";
    String displayKey;
    UUID idleQueueId;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        clinic = clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic("clinic-" + suffix, "Sharma Clinic", "Asia/Kolkata", "adm-" + suffix, "s3cret-pass"));
        admin = new StaffPrincipal(UUID.randomUUID(), "adm-" + suffix, StaffRole.CLINIC_ADMIN, clinic.id(), null);
        receptionist =
                new StaffPrincipal(UUID.randomUUID(), "rec-" + suffix, StaffRole.RECEPTIONIST, clinic.id(), null);
        UUID d1 = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Rajesh Sharma", "Room 2"))
                .id();
        UUID d2 = doctorAdmin
                .create(admin, new DoctorDetails("Dr. Neha Gill", null))
                .id();
        doctor = new StaffPrincipal(UUID.randomUUID(), "d1-" + suffix, StaffRole.DOCTOR, clinic.id(), d1);
        otherDoctor = new StaffPrincipal(UUID.randomUUID(), "d2-" + suffix, StaffRole.DOCTOR, clinic.id(), d2);
        CreatedQueue created = queueAdmin.create(
                admin, queueSlug, new QueueDetails("General OPD", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(d1)));
        queueId = created.queue().id();
        displayKey = created.displayKey();
        // a second queue that nobody has opened, assigned to the other doctor
        idleQueueId = queueAdmin
                .create(admin, "idle", new QueueDetails("Idle", QueueMode.SINGLE, QrMode.STATIC, "B", Set.of(d2)))
                .queue()
                .id();
    }

    // --- patient: join page ---

    @Test
    void joinPageShowsTheQueueAndAFormWithoutOpeningASession() throws Exception {
        days.open(receptionist, queueId);

        mvc.perform(get(joinUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Sharma Clinic")))
                .andExpect(content().string(containsString("General OPD")))
                .andExpect(content().string(containsString("Dr. Rajesh Sharma")))
                .andExpect(content().string(containsString("name=\"idempotencyKey\"")))
                .andExpect(content().string(containsString("Get my token")))
                // patients must not get a session cookie (no Redis session per scan)
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void joinPageHasNoFormWhenTheQueueIsNotOpenPausedOrClosed() throws Exception {
        mvc.perform(get(joinUrl()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("not open yet")))
                .andExpect(content().string(not(containsString("name=\"name\""))));

        days.open(receptionist, queueId);
        days.pause(receptionist, queueId);
        mvc.perform(get(joinUrl()))
                .andExpect(content().string(containsString("paused")))
                .andExpect(content().string(not(containsString("name=\"name\""))));

        days.close(receptionist, queueId);
        mvc.perform(get(joinUrl()))
                .andExpect(content().string(containsString("closed for today")))
                .andExpect(content().string(not(containsString("name=\"name\""))));
    }

    @Test
    void unknownQueueIs404() throws Exception {
        mvc.perform(get("/j/" + clinic.slug() + "/nope")).andExpect(status().isNotFound());
    }

    @Test
    void joiningRedirectsToTheTokenPageAndADoubleSubmitGivesTheSameToken() throws Exception {
        days.open(receptionist, queueId);
        UUID key = UUID.randomUUID();

        String first = joinRedirect("Asha Verma", "", key);
        String second = joinRedirect("Asha Verma", "", key); // tap twice / retry

        assertThat(first).matches("/t/[0-9A-Za-z]{22}");
        assertThat(second).isEqualTo(first);
        assertThat(queries.publicQueue(clinic.slug(), queueSlug).waiting()).isEqualTo(1);

        mvc.perform(get(first))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A-001")))
                .andExpect(content().string(containsString("Waiting")))
                .andExpect(content().string(containsString("/stream")))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void joiningWithoutConsentShowsTheFormAgainAndCreatesNothing() throws Exception {
        days.open(receptionist, queueId);

        mvc.perform(post(joinUrl())
                        .param("name", "No Consent")
                        .param("idempotencyKey", UUID.randomUUID().toString()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("tick the box")))
                .andExpect(content().string(containsString("value=\"No Consent\"")));

        assertThat(queries.publicQueue(clinic.slug(), queueSlug).waiting()).isZero();
    }

    @Test
    void joiningWithABadPhoneNumberOrABlankNameShowsAnErrorOnTheForm() throws Exception {
        days.open(receptionist, queueId);

        mvc.perform(joinRequest("Asha", "12345", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert error")));
        mvc.perform(joinRequest("   ", "", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert error")));

        assertThat(queries.publicQueue(clinic.slug(), queueSlug).waiting()).isZero();
    }

    @Test
    void aSecondTokenForTheSamePhoneIsRefusedOnTheForm() throws Exception {
        days.open(receptionist, queueId);
        joinRedirect("Asha", "9876543210", UUID.randomUUID());

        mvc.perform(joinRequest("Asha again", "98765 43210", UUID.randomUUID()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert error")))
                .andExpect(content().string(containsString("A-001")));
    }

    @Test
    void joiningAPausedQueueIsRefusedOnTheForm() throws Exception {
        days.open(receptionist, queueId);
        UUID key = UUID.randomUUID();
        days.pause(receptionist, queueId);

        mvc.perform(joinRequest("Late Comer", "", key))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("alert")));
        assertThat(queries.publicQueue(clinic.slug(), queueSlug).waiting()).isZero();
    }

    // --- patient: token page ---

    @Test
    void tokenPageIsAvailableInHindiAndTheChoiceIsRemembered() throws Exception {
        days.open(receptionist, queueId);
        String path = joinRedirect("Asha", "", UUID.randomUUID());

        MvcResult hindi = mvc.perform(get(path).param("lang", "hi"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("प्रतीक्षा में")))
                .andExpect(content().string(not(containsString(">Waiting<"))))
                .andReturn();
        // the language cookie carries the choice to the next page load
        String cookie = hindi.getResponse().getHeader("Set-Cookie");
        assertThat(cookie).contains("baari-lang=hi");

        mvc.perform(get(path).cookie(hindi.getResponse().getCookie("baari-lang")))
                .andExpect(content().string(containsString("प्रतीक्षा में")));
        mvc.perform(get(path)).andExpect(content().string(containsString("Waiting")));
    }

    @Test
    void tokenPageShowsTheWaitEstimateInEnglishAndHindiAndTheStreamCarriesIt() throws Exception {
        days.open(receptionist, queueId);
        String first = joinRedirect("Asha", "", UUID.randomUUID());
        joinRedirect("Bina", "", UUID.randomUUID());
        String third = joinRedirect("Chitra", "", UUID.randomUUID());

        // Default estimate (6 min a patient plus a 30 s gap): the first is next, the third waits 10 to 15 min.
        mvc.perform(get(first)).andExpect(content().string(containsString(">Your turn: in a few minutes</p>")));
        mvc.perform(get(third)).andExpect(content().string(containsString(">Your turn in about 10–15 minutes</p>")));
        mvc.perform(get(third).param("lang", "hi"))
                .andExpect(content().string(containsString(">आपकी बारी लगभग 10–15 मिनट में</p>")));

        String code = third.substring("/t/".length());
        MvcResult stream = mvc.perform(
                        get("/api/public/tokens/" + code + "/stream").accept("text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentAsString())
                .contains("event:snapshot")
                .contains("\"displayCode\":\"A-003\"")
                .contains("\"eta\":{\"kind\":\"RANGE\",\"fromMinutes\":10,\"toMinutes\":15}");
    }

    @Test
    void thePageHasNoEstimateOnceThePatientIsCalled() throws Exception {
        days.open(receptionist, queueId);
        String path = joinRedirect("Asha", "", UUID.randomUUID());
        actions.callNext(doctor, queueId, null);

        mvc.perform(get(path))
                .andExpect(content().string(containsString("id=\"eta\" hidden")))
                .andExpect(content().string(not(containsString(">Your turn: in a few minutes</p>"))));
    }

    @Test
    void whenCalledTheTokenPageShowsTheRoom() throws Exception {
        days.open(receptionist, queueId);
        String path = joinRedirect("Asha", "", UUID.randomUUID());
        actions.callNext(doctor, queueId, null);

        mvc.perform(get(path))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("It is your turn")))
                .andExpect(content().string(containsString("Please go to Room 2")));
    }

    @Test
    void cancellingFromTheTokenPageWorksAndIsHarmlessTwice() throws Exception {
        days.open(receptionist, queueId);
        String path = joinRedirect("Asha", "", UUID.randomUUID());

        mvc.perform(post(path + "/cancel")).andExpect(redirectedUrl(path));
        mvc.perform(post(path + "/cancel")).andExpect(redirectedUrl(path));

        mvc.perform(get(path)).andExpect(content().string(containsString("Cancelled")));
    }

    @Test
    void unknownTokenIs404() throws Exception {
        mvc.perform(get("/t/doesnotexist0000000000")).andExpect(status().isNotFound());
    }

    // --- staff console ---

    @Test
    void consoleOpensForClinicStaffAndDrawsItsControls() throws Exception {
        mvc.perform(get("/staff/queues/" + queueId).with(as(receptionist)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("General OPD")))
                .andExpect(content().string(containsString("Call next")))
                .andExpect(content().string(containsString("csrf-token")))
                .andExpect(content().string(containsString("/js/console.js")));
        mvc.perform(get("/staff/queues/" + queueId).with(as(admin))).andExpect(status().isOk());
        mvc.perform(get("/staff/queues/" + queueId).with(as(doctor))).andExpect(status().isOk());
    }

    @Test
    void consoleOfAPoolQueueLetsReceptionChooseTheCallingDoctorButNotDoctors() throws Exception {
        UUID d1 = doctor.doctorId();
        UUID d2 = otherDoctor.doctorId();
        UUID pool = queueAdmin
                .create(admin, "pool", new QueueDetails("Pool", QueueMode.POOL, QrMode.STATIC, "C", Set.of(d1, d2)))
                .queue()
                .id();

        mvc.perform(get("/staff/queues/" + pool).with(as(receptionist)))
                .andExpect(content().string(containsString("id=\"doctor\"")))
                .andExpect(content().string(containsString("Dr. Neha Gill")));
        mvc.perform(get("/staff/queues/" + pool).with(as(doctor)))
                .andExpect(content().string(not(containsString("id=\"doctor\""))));
    }

    @Test
    void consoleAccessRules() throws Exception {
        // not logged in: sent to the login page
        mvc.perform(get("/staff/queues/" + queueId))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
        // a doctor not assigned to the queue sees "not found"
        mvc.perform(get("/staff/queues/" + queueId).with(as(otherDoctor))).andExpect(status().isNotFound());
        // another clinic's staff: not found
        ClinicView other = clinicAdmin.createClinic(
                PLATFORM,
                new NewClinic(
                        "other-" + UUID.randomUUID().toString().substring(0, 8),
                        "Other Clinic",
                        "Asia/Kolkata",
                        "oadm-" + UUID.randomUUID().toString().substring(0, 8),
                        "s3cret-pass"));
        StaffPrincipal otherAdmin =
                new StaffPrincipal(UUID.randomUUID(), "oa", StaffRole.CLINIC_ADMIN, other.id(), null);
        mvc.perform(get("/staff/queues/" + queueId).with(as(otherAdmin))).andExpect(status().isNotFound());
        // the platform admin runs no queues
        mvc.perform(get("/staff/queues/" + queueId).with(as(PLATFORM))).andExpect(status().isForbidden());
    }

    @Test
    void consoleListShowsAllQueuesToReceptionButOnlyOwnToDoctors() throws Exception {
        mvc.perform(get("/staff/queues").with(as(receptionist)))
                .andExpect(content().string(containsString("General OPD")))
                .andExpect(content().string(containsString("Idle")));
        mvc.perform(get("/staff/queues").with(as(doctor)))
                .andExpect(content().string(containsString("General OPD")))
                .andExpect(content().string(not(containsString(">Idle<"))));
    }

    @Test
    void staffStreamStartsWithTheFullBoardAndIsTenantChecked() throws Exception {
        days.open(receptionist, queueId);
        tokens.issueWalkIn(
                receptionist,
                queueId,
                new com.baari.queue.WalkInRequest("Visible To Staff", null, VisitType.NEW, Priority.NORMAL, null));

        MvcResult result = mvc.perform(get("/api/staff/queues/" + queueId + "/stream")
                        .with(as(receptionist))
                        .accept("text/event-stream"))
                .andExpect(request().asyncStarted())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("event:snapshot")
                .contains("Visible To Staff")
                .contains("\"waiting\"")
                .contains("\"estimates\":{\"");

        mvc.perform(get("/api/staff/queues/" + queueId + "/stream").with(as(otherDoctor)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/staff/queues/" + queueId + "/stream")).andExpect(status().isUnauthorized());
    }

    // --- TV display ---

    @Test
    void displayPageNeedsTheKeyAndShowsNoPatientNames() throws Exception {
        days.open(receptionist, queueId);
        joinRedirect("Hidden Name One", "", UUID.randomUUID());
        joinRedirect("Hidden Name Two", "", UUID.randomUUID());
        actions.callNext(doctor, queueId, null);

        mvc.perform(get("/display/" + queueId).param("key", "wrong")).andExpect(status().isNotFound());
        mvc.perform(get("/display/" + queueId)).andExpect(status().isBadRequest());

        mvc.perform(get("/display/" + queueId).param("key", displayKey))
                .andExpect(status().isOk())
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(content().string(containsString("Now serving")))
                .andExpect(content().string(containsString("A-001")))
                .andExpect(content().string(containsString("Room 2")))
                .andExpect(content().string(containsString("A-002")))
                .andExpect(content().string(not(containsString("Hidden"))));
    }

    @Test
    void displayKeyOfAnotherQueueDoesNotWork() throws Exception {
        CreatedQueue another = queueAdmin.create(
                admin, "second", new QueueDetails("Second", QueueMode.POOL, QrMode.STATIC, "D", Set.of()));
        mvc.perform(get("/display/" + queueId).param("key", another.displayKey()))
                .andExpect(status().isNotFound());
    }

    // --- poster ---

    @Test
    void posterShowsTheJoinLinkToTheClinicAdminOnly() throws Exception {
        mvc.perform(get("/admin/queues/" + queueId + "/poster").with(as(admin)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/j/" + clinic.slug() + "/" + queueSlug)))
                .andExpect(content().string(containsString("Sharma Clinic")));
        mvc.perform(get("/admin/queues/" + queueId + "/poster").with(as(receptionist)))
                .andExpect(status().isForbidden());
    }

    // --- helpers ---

    private String joinUrl() {
        return "/j/" + clinic.slug() + "/" + queueSlug;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder joinRequest(
            String name, String phone, UUID key) {
        return post(joinUrl())
                .param("name", name)
                .param("phone", phone)
                .param("visitType", "NEW")
                .param("consent", "on")
                .param("idempotencyKey", key.toString());
    }

    /** Submits the join form and returns the redirect target (the token page path). */
    private String joinRedirect(String name, String phone, UUID key) throws Exception {
        return mvc.perform(joinRequest(name, phone, key))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    private static RequestPostProcessor as(StaffPrincipal p) {
        return user(new StaffUserDetails(p, null, true, false));
    }
}
