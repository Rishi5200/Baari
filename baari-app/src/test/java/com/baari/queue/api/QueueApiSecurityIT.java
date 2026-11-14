package com.baari.queue.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * Who may call the queue API, and what errors look like (PLAN §9.2, Appendix A): the tenant
 * isolation matrix for every staff endpoint (another clinic → 404, nothing changes), role and
 * login rules, CSRF, and RFC 9457 bodies for the queue's business errors.
 */
class QueueApiSecurityIT extends QueueApiTestSupport {

    /** Every staff queue endpoint, for one queue and one token of clinic A. */
    private List<String[]> staffEndpoints(UUID queueId, String tokenId) {
        String q = "/api/staff/queues/" + queueId;
        String t = "/api/staff/tokens/" + tokenId;
        return List.of(
                new String[] {"POST", q + "/days/today/open"},
                new String[] {"POST", q + "/days/today/pause"},
                new String[] {"POST", q + "/days/today/resume"},
                new String[] {"POST", q + "/days/today/close"},
                new String[] {"GET", q + "/board"},
                new String[] {"POST", q + "/tokens"},
                new String[] {"POST", q + "/call-next"},
                new String[] {"POST", t + "/call"},
                new String[] {"POST", t + "/start"},
                new String[] {"POST", t + "/complete"},
                new String[] {"POST", t + "/no-show"},
                new String[] {"POST", t + "/recall"},
                new String[] {"POST", t + "/cancel"},
                new String[] {"POST", t + "/priority"});
    }

    /** A JSON body that is valid for whichever endpoint gets it. */
    private static final Map<String, Object> ANY_BODY =
            Map.of("name", "X", "visitType", "NEW", "priority", "NORMAL", "force", true);

    private void expect(StaffPrincipal who, String[] endpoint, int status) throws Exception {
        var request = endpoint[0].equals("GET")
                ? get(endpoint[1]).with(as(who))
                : post(endpoint[1])
                        .with(as(who))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(ANY_BODY));
        mvc.perform(request).andExpect(status().is(status));
    }

    @Nested
    class TenantIsolation {

        @Test
        void anotherClinicGetsNotFoundOnEveryStaffEndpointAndChangesNothing() throws Exception {
            openDay(single);
            String tokenId = walkIn(single, "Asha", "NORMAL");
            StaffPrincipal otherAdmin = new StaffPrincipal(
                    UUID.randomUUID(),
                    unique("b"),
                    StaffRole.CLINIC_ADMIN,
                    newClinic().id(),
                    null);
            StaffPrincipal otherReceptionist = new StaffPrincipal(
                    UUID.randomUUID(), unique("b"), StaffRole.RECEPTIONIST, otherAdmin.clinicId(), null);

            for (String[] endpoint : staffEndpoints(single, tokenId)) {
                expect(otherAdmin, endpoint, 404);
                expect(otherReceptionist, endpoint, 404);
            }

            staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                    .andExpect(jsonPath("$.day.status").value("OPEN"))
                    .andExpect(jsonPath("$.day.lastEventSeq").value(2))
                    .andExpect(jsonPath("$.waiting[0].id").value(tokenId))
                    .andExpect(jsonPath("$.waiting[0].status").value("WAITING"));
        }

        @Test
        void aDoctorNotOnTheQueueGetsNotFound() throws Exception {
            openDay(single);
            String tokenId = walkIn(single, "Asha", "NORMAL");

            for (String[] endpoint : staffEndpoints(single, tokenId)) {
                expect(doctor2, endpoint, 404);
            }
        }
    }

    @Nested
    class LoginAndRoles {

        @Test
        void staffApiNeedsALogin() throws Exception {
            mvc.perform(get("/api/staff/queues/" + single + "/board")).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/staff/queues/" + single + "/days/today/open").with(csrf()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void platformAdminRunsNoQueues() throws Exception {
            staffGet(PLATFORM, "/api/staff/queues/" + single + "/board").andExpect(status().isForbidden());
        }

        @Test
        void staffChangesNeedTheCsrfToken() throws Exception {
            mvc.perform(post("/api/staff/queues/" + single + "/days/today/open").with(as(receptionist)))
                    .andExpect(status().isForbidden());
            staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                    .andExpect(jsonPath("$.day").value(nullValue()));
        }

        @Test
        void everyClinicRoleCanRunItsQueue() throws Exception {
            staffPost(doctor1, "/api/staff/queues/" + single + "/days/today/open")
                    .andExpect(status().isOk());
            staffPost(receptionist, "/api/staff/queues/" + single + "/days/today/pause")
                    .andExpect(status().isOk());
            staffPost(admin, "/api/staff/queues/" + single + "/days/today/resume")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("OPEN"));
        }
    }

    @Nested
    class Errors {

        private String joinUrl() {
            return "/api/public/queues/" + single + "/tokens";
        }

        @Test
        void joiningBeforeTheDayIsOpenedIsAConflict() throws Exception {
            publicPost(joinUrl(), joinBody("Asha", UUID.randomUUID()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/queue-not-opened"));
        }

        @Test
        void aPausedQueueAnswers423() throws Exception {
            openDay(single);
            staffPost(receptionist, "/api/staff/queues/" + single + "/days/today/pause");

            publicPost(joinUrl(), joinBody("Asha", UUID.randomUUID()))
                    .andExpect(status().isLocked())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/queue-paused"));
        }

        @Test
        void aSecondTokenForTheSamePhoneNamesOnlyTheDisplayCode() throws Exception {
            openDay(single);
            Map<String, Object> first = Map.of(
                    "name",
                    "Asha",
                    "phone",
                    "98765 43210",
                    "visitType",
                    "NEW",
                    "consentVersion",
                    "v1",
                    "idempotencyKey",
                    UUID.randomUUID().toString());
            String code = read(publicPost(joinUrl(), first).andExpect(status().isCreated()), "$.publicCode");
            Map<String, Object> second = Map.of(
                    "name",
                    "Asha",
                    "phone",
                    "+919876543210",
                    "visitType",
                    "NEW",
                    "consentVersion",
                    "v1",
                    "idempotencyKey",
                    UUID.randomUUID().toString());

            publicPost(joinUrl(), second)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/already-in-queue"))
                    .andExpect(jsonPath("$.detail").value("You already hold token A-001 in this queue."))
                    .andExpect(content().string(not(containsString(code))));
        }

        @Test
        void badInputIs400WithoutEchoingIt() throws Exception {
            openDay(single);
            Map<String, Object> badPhone = Map.of(
                    "name",
                    "Asha",
                    "phone",
                    "12345",
                    "visitType",
                    "NEW",
                    "consentVersion",
                    "v1",
                    "idempotencyKey",
                    UUID.randomUUID().toString());
            publicPost(joinUrl(), badPhone)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/invalid-phone"))
                    .andExpect(content().string(not(containsString("12345"))));

            // No idempotency key in body or header.
            publicPost(joinUrl(), Map.of("name", "Asha", "visitType", "NEW", "consentVersion", "v1"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/validation"));

            // Unknown enum value.
            publicPost(joinUrl(), Map.of("name", "Asha", "visitType", "CHECKUP", "consentVersion", "v1"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownQueueOrTokenIsNotFound() throws Exception {
            publicGet("/api/public/tokens/0000000000000000000000").andExpect(status().isNotFound());
            publicPost("/api/public/tokens/0000000000000000000000/cancel", null).andExpect(status().isNotFound());
            publicGet("/api/public/queues/" + clinic.slug() + "/no-such-queue").andExpect(status().isNotFound());
            publicPost("/api/public/queues/" + UUID.randomUUID() + "/tokens", joinBody("Asha", UUID.randomUUID()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void aPatientCancelsTheirOwnTokenOnce() throws Exception {
            openDay(single);
            String code = read(publicPost(joinUrl(), joinBody("Asha", UUID.randomUUID())), "$.publicCode");

            publicPost("/api/public/tokens/" + code + "/cancel", null)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("CANCELLED"));
            publicPost("/api/public/tokens/" + code + "/cancel", null)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://baari.app/problems/illegal-transition"));
        }

        @Test
        void thePublicTokenPageShowsNoPersonalData() throws Exception {
            openDay(single);
            Map<String, Object> body = Map.of(
                    "name",
                    "Asha Verma",
                    "phone",
                    "98765 43210",
                    "visitType",
                    "NEW",
                    "consentVersion",
                    "v1",
                    "idempotencyKey",
                    UUID.randomUUID().toString());
            String code = read(publicPost(joinUrl(), body), "$.publicCode");

            publicGet("/api/public/tokens/" + code)
                    .andExpect(status().isOk())
                    .andExpect(content().string(not(containsString("Asha"))))
                    .andExpect(content().string(not(containsString("3210"))));
        }
    }
}
