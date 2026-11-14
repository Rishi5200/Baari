package com.baari.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.NewClinic;
import com.baari.clinic.NewStaffUser;
import com.baari.clinic.StaffAdminService;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.MutableClock;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Staff login end to end through the real filter chain: form login, CSRF, Redis-backed sessions,
 * logout and the 5-failures / 15-minutes lockout (PLAN §12 Phase 1 "Done when").
 */
@AutoConfigureMockMvc
class StaffLoginIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse";
    private static final MutableClock CLOCK = MutableClock.at(Instant.parse("2026-11-14T04:30:00Z"));

    @TestBean
    Clock clock;

    static Clock clock() {
        return CLOCK;
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    StaffAdminService staffAdmin;

    @Autowired
    StringRedisTemplate redis;

    String username;
    StaffPrincipal admin;

    @BeforeEach
    void createClinicWithAdmin() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        username = "admin-" + suffix;
        var platform = new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);
        var clinic = clinicAdmin.createClinic(
                platform, new NewClinic("clinic-" + suffix, "Clinic", "Asia/Kolkata", username, PASSWORD));
        admin = new StaffPrincipal(UUID.randomUUID(), username, StaffRole.CLINIC_ADMIN, clinic.id(), null);
    }

    private void loginFails(String user, String password) throws Exception {
        mvc.perform(formLogin().user(user).password(password))
                .andExpect(redirectedUrl("/login?error"))
                .andExpect(unauthenticated());
    }

    private Cookie loginSucceeds(String user, String password) throws Exception {
        MvcResult result = mvc.perform(formLogin().user(user).password(password))
                .andExpect(redirectedUrl("/"))
                .andExpect(authenticated().withUsername(username))
                .andReturn();
        Cookie session = result.getResponse().getCookie("SESSION");
        assertThat(session).as("Spring Session cookie").isNotNull();
        return session;
    }

    @Test
    void anonymousUserIsSentToLoginPage() throws Exception {
        mvc.perform(get("/")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginPageIsPublicAndCarriesCsrfToken() throws Exception {
        mvc.perform(get("/login"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"_csrf\"")));
    }

    @Test
    void correctPasswordLogsInWithSessionStoredInRedis() throws Exception {
        Cookie session = loginSucceeds(username, PASSWORD);

        assertThat(redis.keys("baari:session:sessions:*")).isNotEmpty();
        // The home page is rendered from the session alone: the StaffPrincipal parameter is filled
        // by TenantContext from the Redis-backed session.
        mvc.perform(get("/").cookie(session))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(username)))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("CLINIC_ADMIN")));
    }

    @Test
    void usernameIsCaseInsensitive() throws Exception {
        loginSucceeds(username.toUpperCase(), PASSWORD);
    }

    @Test
    void wrongPasswordAndUnknownUserFail() throws Exception {
        loginFails(username, "wrong-password");
        loginFails("nobody-" + UUID.randomUUID(), PASSWORD);
    }

    @Test
    void fiveWrongPasswordsLockTheAccountForFifteenMinutes() throws Exception {
        for (int i = 0; i < 5; i++) {
            loginFails(username, "wrong-password");
        }

        loginFails(username, PASSWORD); // locked: even the right password is refused

        CLOCK.advance(Duration.ofMinutes(14));
        loginFails(username, PASSWORD);

        CLOCK.advance(Duration.ofMinutes(1));
        loginSucceeds(username, PASSWORD);
    }

    @Test
    void successfulLoginResetsTheFailureCount() throws Exception {
        for (int i = 0; i < 4; i++) {
            loginFails(username, "wrong-password");
        }
        loginSucceeds(username, PASSWORD);
        for (int i = 0; i < 4; i++) {
            loginFails(username, "wrong-password");
        }

        loginSucceeds(username, PASSWORD);
    }

    @Test
    void disabledUserCannotLogIn() throws Exception {
        String receptionist = "rec-" + UUID.randomUUID().toString().substring(0, 8);
        var created = staffAdmin.create(admin, new NewStaffUser(receptionist, PASSWORD, StaffRole.RECEPTIONIST, null));
        staffAdmin.setEnabled(admin, created.id(), false);

        loginFails(receptionist, PASSWORD);
    }

    @Test
    void loginWithoutCsrfTokenIsRejected() throws Exception {
        mvc.perform(post("/login").param("username", username).param("password", PASSWORD))
                .andExpect(status().isForbidden())
                .andExpect(unauthenticated());
    }

    @Test
    void logoutNeedsCsrfAndEndsTheSession() throws Exception {
        Cookie session = loginSucceeds(username, PASSWORD);

        mvc.perform(post("/logout").cookie(session)).andExpect(status().isForbidden());

        mvc.perform(post("/logout").with(csrf()).cookie(session)).andExpect(redirectedUrl("/login?logout"));

        mvc.perform(get("/").cookie(session))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login"));
    }
}
