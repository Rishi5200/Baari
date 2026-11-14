package com.baari.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.ClinicView;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.DoctorView;
import com.baari.clinic.NewClinic;
import com.baari.clinic.NewStaffUser;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueDetails;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffAdminService;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.identity.internal.StaffUserDetails;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * The Phase 1 "Done when" checks at HTTP level, through the real security filter chain:
 *
 * <ul>
 *   <li>Tenant isolation matrix: a clinic B admin gets 404 on every clinic A resource (PLAN §9.2).
 *   <li>DOCTOR cannot manage staff, RECEPTIONIST cannot manage doctors (403), and the other role
 *       boundaries of the admin pages.
 * </ul>
 */
@AutoConfigureMockMvc
class AdminPagesIT extends AbstractIntegrationTest {

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
    StaffAdminService staffAdmin;

    ClinicView clinicA;
    ClinicView clinicB;
    StaffPrincipal adminA;
    StaffPrincipal adminB;
    StaffPrincipal receptionistA;
    StaffPrincipal doctorUserA;
    DoctorView doctorA;
    UUID queueA;
    UUID staffUserA;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClinicView newClinic() {
        return clinicAdmin.createClinic(
                PLATFORM, new NewClinic(unique("clinic"), "Clinic", "Asia/Kolkata", unique("admin"), "s3cret-pass"));
    }

    private static StaffPrincipal principal(StaffRole role, UUID clinicId, UUID doctorId) {
        return new StaffPrincipal(UUID.randomUUID(), unique("u"), role, clinicId, doctorId);
    }

    /** Logs the request in as this principal, exactly as the session would after a form login. */
    private static RequestPostProcessor as(StaffPrincipal p) {
        return user(new StaffUserDetails(p, null, true, false));
    }

    @BeforeEach
    void setUp() {
        clinicA = newClinic();
        clinicB = newClinic();
        adminA = principal(StaffRole.CLINIC_ADMIN, clinicA.id(), null);
        adminB = principal(StaffRole.CLINIC_ADMIN, clinicB.id(), null);
        doctorA = doctorAdmin.create(adminA, new DoctorDetails(unique("Dr. A"), "Room 1"));
        receptionistA = principal(StaffRole.RECEPTIONIST, clinicA.id(), null);
        doctorUserA = principal(StaffRole.DOCTOR, clinicA.id(), doctorA.id());
        queueA = queueAdmin
                .create(
                        adminA,
                        "general",
                        new QueueDetails("General", QueueMode.POOL, QrMode.STATIC, "A", Set.of(doctorA.id())))
                .queue()
                .id();
        staffUserA = staffAdmin
                .create(adminA, new NewStaffUser(unique("rec"), "s3cret-pass", StaffRole.RECEPTIONIST, null))
                .id();
    }

    // --- request builders, with valid bodies so a 403/404 can't be a validation error in disguise

    private MockHttpServletRequestBuilder createDoctor() {
        return post("/admin/doctors").with(csrf()).param("name", "Dr. New").param("roomLabel", "R9");
    }

    private MockHttpServletRequestBuilder updateDoctor(UUID id) {
        return post("/admin/doctors/{id}", id)
                .with(csrf())
                .param("name", "Hijacked")
                .param("roomLabel", "");
    }

    private MockHttpServletRequestBuilder setDoctorActive(UUID id) {
        return post("/admin/doctors/{id}/active", id).with(csrf()).param("active", "false");
    }

    private MockHttpServletRequestBuilder createQueue() {
        return post("/admin/queues")
                .with(csrf())
                .param("slug", unique("q"))
                .param("name", "Q")
                .param("mode", "POOL")
                .param("qrMode", "STATIC")
                .param("displayCodePrefix", "A");
    }

    private MockHttpServletRequestBuilder updateQueue(UUID id) {
        return post("/admin/queues/{id}", id)
                .with(csrf())
                .param("name", "Hijacked")
                .param("mode", "POOL")
                .param("qrMode", "STATIC")
                .param("displayCodePrefix", "A");
    }

    private MockHttpServletRequestBuilder createStaff() {
        return post("/admin/staff")
                .with(csrf())
                .param("username", unique("new"))
                .param("password", "s3cret-pass")
                .param("role", "RECEPTIONIST");
    }

    private MockHttpServletRequestBuilder changeRole(UUID id) {
        return post("/admin/staff/{id}/role", id).with(csrf()).param("role", "CLINIC_ADMIN");
    }

    private MockHttpServletRequestBuilder resetPassword(UUID id) {
        return post("/admin/staff/{id}/password", id).with(csrf()).param("password", "hijacked-pass");
    }

    private MockHttpServletRequestBuilder setStaffEnabled(UUID id) {
        return post("/admin/staff/{id}/enabled", id).with(csrf()).param("enabled", "false");
    }

    private void expectStatus(StaffPrincipal who, List<MockHttpServletRequestBuilder> requests, int expected)
            throws Exception {
        for (MockHttpServletRequestBuilder request : requests) {
            mvc.perform(request.with(as(who))).andExpect(status().is(expected));
        }
    }

    // --- Done when: tenant isolation matrix

    @Test
    void otherClinicsAdminGets404OnEveryClinicAResource() throws Exception {
        expectStatus(
                adminB,
                List.of(
                        get("/admin/doctors/{id}/edit", doctorA.id()),
                        updateDoctor(doctorA.id()),
                        setDoctorActive(doctorA.id()),
                        get("/admin/queues/{id}/edit", queueA),
                        updateQueue(queueA),
                        get("/admin/staff/{id}", staffUserA),
                        changeRole(staffUserA),
                        resetPassword(staffUserA),
                        setStaffEnabled(staffUserA)),
                404);

        // ...and nothing of clinic A changed.
        DoctorView doctor = doctorAdmin.get(adminA, doctorA.id());
        assertThat(doctor.name()).isEqualTo(doctorA.name());
        assertThat(doctor.active()).isTrue();
        assertThat(queueAdmin.get(adminA, queueA).name()).isEqualTo("General");
        var user = staffAdmin.get(adminA, staffUserA);
        assertThat(user.role()).isEqualTo(StaffRole.RECEPTIONIST);
        assertThat(user.enabled()).isTrue();
    }

    @Test
    void otherClinicsListsDoNotShowClinicAData() throws Exception {
        mvc.perform(get("/admin/doctors").with(as(adminB)))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString(doctorA.name()))));
    }

    @Test
    void clinicIdInRequestIsIgnored() throws Exception {
        String name = unique("Dr. Sneaky");
        mvc.perform(post("/admin/doctors")
                        .with(csrf())
                        .with(as(adminA))
                        .param("name", name)
                        .param("clinicId", clinicB.id().toString()))
                .andExpect(redirectedUrl("/admin/doctors"));

        assertThat(doctorAdmin.list(adminA)).extracting(DoctorView::name).contains(name);
        assertThat(doctorAdmin.list(adminB)).extracting(DoctorView::name).doesNotContain(name);
    }

    // --- Done when: role boundaries

    @Test
    void doctorCannotManageStaff() throws Exception {
        expectStatus(
                doctorUserA,
                List.of(
                        get("/admin/staff"),
                        get("/admin/staff/new"),
                        createStaff(),
                        get("/admin/staff/{id}", staffUserA),
                        changeRole(staffUserA),
                        resetPassword(staffUserA),
                        setStaffEnabled(staffUserA)),
                403);
    }

    @Test
    void receptionistCannotManageDoctors() throws Exception {
        expectStatus(
                receptionistA,
                List.of(
                        get("/admin/doctors/new"),
                        createDoctor(),
                        get("/admin/doctors/{id}/edit", doctorA.id()),
                        updateDoctor(doctorA.id()),
                        setDoctorActive(doctorA.id())),
                403);
        assertThat(doctorAdmin.get(adminA, doctorA.id()).name()).isEqualTo(doctorA.name());
    }

    @Test
    void receptionistAndDoctorCannotManageQueuesOrStaffOrClinics() throws Exception {
        for (StaffPrincipal p : List.of(receptionistA, doctorUserA)) {
            expectStatus(
                    p,
                    List.of(
                            get("/admin/queues/new"),
                            createQueue(),
                            updateQueue(queueA),
                            get("/admin/staff"),
                            get("/platform/clinics")),
                    403);
        }
    }

    @Test
    void receptionistAndDoctorCanViewDoctorAndQueueLists() throws Exception {
        for (StaffPrincipal p : List.of(receptionistA, doctorUserA)) {
            mvc.perform(get("/admin/doctors").with(as(p)))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString(doctorA.name())))
                    .andExpect(content().string(not(containsString("Add doctor"))));
            mvc.perform(get("/admin/queues").with(as(p))).andExpect(status().isOk());
        }
    }

    @Test
    void clinicAdminCannotUsePlatformPagesAndPlatformAdminCannotUseClinicPages() throws Exception {
        expectStatus(adminA, List.of(get("/platform/clinics"), get("/platform/clinics/new")), 403);
        expectStatus(PLATFORM, List.of(get("/admin/doctors"), get("/admin/staff"), createDoctor()), 403);
    }

    // --- the pages work for the roles that may use them

    @Test
    void clinicAdminManagesDoctors() throws Exception {
        String name = unique("Dr. Kaur");
        mvc.perform(post("/admin/doctors")
                        .with(csrf())
                        .with(as(adminA))
                        .param("name", name)
                        .param("roomLabel", "Room 2"))
                .andExpect(redirectedUrl("/admin/doctors"));

        mvc.perform(get("/admin/doctors").with(as(adminA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(name)));
    }

    @Test
    void invalidFormIsShownAgainWithErrors() throws Exception {
        mvc.perform(post("/admin/doctors")
                        .with(csrf())
                        .with(as(adminA))
                        .param("name", "")
                        .param("roomLabel", ""))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("field-error")));
    }

    @Test
    void creatingAQueueShowsTheDisplayKeyOnce() throws Exception {
        mvc.perform(createQueue().with(as(adminA)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("TV display key")));
    }

    @Test
    void duplicateQueueSlugIsShownOnTheForm() throws Exception {
        mvc.perform(post("/admin/queues")
                        .with(csrf())
                        .with(as(adminA))
                        .param("slug", "general")
                        .param("name", "Again")
                        .param("mode", "POOL")
                        .param("qrMode", "STATIC")
                        .param("displayCodePrefix", "A"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("already exists")));
    }

    @Test
    void clinicAdminManagesStaff() throws Exception {
        mvc.perform(createStaff().with(as(adminA))).andExpect(redirectedUrl("/admin/staff"));

        mvc.perform(setStaffEnabled(staffUserA).with(as(adminA)))
                .andExpect(redirectedUrl("/admin/staff/" + staffUserA));
        assertThat(staffAdmin.get(adminA, staffUserA).enabled()).isFalse();
    }

    @Test
    void platformAdminCreatesClinic() throws Exception {
        String slug = unique("clinic");
        mvc.perform(post("/platform/clinics")
                        .with(csrf())
                        .with(as(PLATFORM))
                        .param("slug", slug)
                        .param("name", "New Clinic")
                        .param("timezone", "Asia/Kolkata")
                        .param("adminUsername", unique("owner"))
                        .param("adminPassword", "s3cret-pass"))
                .andExpect(redirectedUrl("/platform/clinics"));

        mvc.perform(get("/platform/clinics").with(as(PLATFORM)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(slug)));
    }

    @Test
    void forbiddenAndNotFoundRenderTheHtmlErrorPage() throws Exception {
        mvc.perform(get("/admin/doctors/{id}/edit", doctorA.id()).with(as(adminB)))
                .andExpect(status().isNotFound())
                .andExpect(content().string(containsString("Doctor not found.")));
    }
}
