package com.baari.queue.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.baari.clinic.StaffPrincipal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * The PLAN §2.3 journeys J1–J7 at API level (PLAN §12 Phase 2 "Done when"). ETA fields are null
 * until Phase 4, and notifications ("3 ahead") come in Phase 5, so those steps are not checked.
 */
class JourneysIT extends QueueApiTestSupport {

    private ResultActions token(String publicCode) throws Exception {
        return publicGet("/api/public/tokens/" + publicCode);
    }

    private ResultActions callNext(StaffPrincipal who, UUID queueId) throws Exception {
        return staffPost(who, "/api/staff/queues/" + queueId + "/call-next");
    }

    private ResultActions joinWithHeader(String url, UUID key, Object body) throws Exception {
        return mvc.perform(post(url)
                .header("Idempotency-Key", key.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    private void tokenAction(String tokenId, String action) throws Exception {
        staffPost(doctor1, "/api/staff/tokens/" + tokenId + "/" + action).andExpect(status().isOk());
    }

    /** J1: a patient scans, joins, watches the line move, is called and served. */
    @Test
    void j1PatientJoinsAndIsServed() throws Exception {
        openDay(single);
        walkIn(single, "First", "NORMAL");
        walkIn(single, "Second", "NORMAL");
        walkIn(single, "Third", "NORMAL");

        // Scan: the join page shows the queue.
        publicGet("/api/public/queues/" + clinic.slug() + "/dr-sharma")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.queueId").value(single.toString()))
                .andExpect(jsonPath("$.clinicName").value("Sharma Clinic"))
                .andExpect(jsonPath("$.queueName").value("Dr. Sharma"))
                .andExpect(jsonPath("$.doctors", contains("Dr. Rajesh Sharma")))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.waiting").value(3));

        // Join: fourth in line.
        ResultActions joined = publicPost(
                        "/api/public/queues/" + single + "/tokens", joinBody("Asha Verma", UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayCode").value("A-004"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(4))
                .andExpect(jsonPath("$.eta").value(nullValue()))
                .andExpect(jsonPath("$.publicCode", matchesPattern("[0-9A-Za-z]{22}")));
        String code = read(joined, "$.publicCode");
        token(code)
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.peopleAhead").value(3))
                .andExpect(jsonPath("$.nowServing", empty()))
                .andExpect(jsonPath("$.queueStatus").value("OPEN"));

        // The doctor works through the line; the patient sees it move.
        for (int i = 0; i < 3; i++) {
            String id = read(callNext(doctor1, single).andExpect(status().isOk()), "$.id");
            tokenAction(id, "start");
            token(code).andExpect(jsonPath("$.peopleAhead").value(2 - i));
            tokenAction(id, "complete");
        }

        // Called: the token page says so and shows it as now serving.
        String id = read(
                callNext(doctor1, single).andExpect(jsonPath("$.displayCode").value("A-004")), "$.id");
        token(code)
                .andExpect(jsonPath("$.status").value("CALLED"))
                .andExpect(jsonPath("$.position").value(nullValue()))
                .andExpect(jsonPath("$.nowServing", contains("A-004")));

        tokenAction(id, "start");
        token(code).andExpect(jsonPath("$.status").value("IN_CONSULTATION"));
        CLOCK.advance(Duration.ofMinutes(5));
        staffPost(doctor1, "/api/staff/tokens/" + id + "/complete")
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(
                        jsonPath("$.servedByDoctorId").value(doctor1.doctorId().toString()));
        token(code).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    /** J2: reception issues a token to a walk-in without a phone and reads out the code. */
    @Test
    void j2WalkInWithoutAPhone() throws Exception {
        openDay(single);

        staffPost(receptionist, "/api/staff/queues/" + single + "/tokens", walkInBody("Ramesh", "NORMAL"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayCode").value("A-001"))
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1));

        staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.waiting[0].patientName").value("Ramesh"))
                .andExpect(jsonPath("$.waiting[0].phoneLast4").value(nullValue()))
                .andExpect(jsonPath("$.waiting[0].source").value("RECEPTION"));
    }

    /** J3: a called patient is absent, marked no-show after 3 minutes, comes back and is recalled. */
    @Test
    void j3NoShowAndRecall() throws Exception {
        openDay(single);
        String absent = walkIn(single, "W1", "NORMAL");
        walkIn(single, "W2", "NORMAL");
        walkIn(single, "W3", "NORMAL");
        walkIn(single, "W4", "NORMAL");
        callNext(receptionist, single).andExpect(jsonPath("$.id").value(absent));

        CLOCK.advance(Duration.ofMinutes(2));
        staffPost(receptionist, "/api/staff/tokens/" + absent + "/no-show")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://baari.app/problems/grace-not-elapsed"));

        CLOCK.advance(Duration.ofMinutes(1));
        staffPost(receptionist, "/api/staff/tokens/" + absent + "/no-show")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NO_SHOW"));

        staffPost(receptionist, "/api/staff/tokens/" + absent + "/recall")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));

        // Back in line behind two waiting patients (recall-insert-offset = 2).
        staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                .andExpect(jsonPath("$.waiting[*].displayCode", contains("A-002", "A-003", "A-001", "A-004")))
                .andExpect(jsonPath("$.noShow", empty()));
    }

    /** J4: reception marks a waiting patient EMERGENCY; they are next on call-next. */
    @Test
    void j4Emergency() throws Exception {
        openDay(single);
        walkIn(single, "N1", "NORMAL");
        walkIn(single, "N2", "NORMAL");
        String urgent = walkIn(single, "N3", "NORMAL");

        staffPost(receptionist, "/api/staff/tokens/" + urgent + "/priority", Map.of("priority", "EMERGENCY"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.priority").value("EMERGENCY"));

        callNext(doctor1, single)
                .andExpect(jsonPath("$.id").value(urgent))
                .andExpect(jsonPath("$.displayCode").value("A-003"));
    }

    /** J5: the patient taps Join twice / the network retries: the same token comes back. */
    @Test
    void j5DoubleSubmitReturnsTheSameToken() throws Exception {
        openDay(single);
        UUID key = UUID.randomUUID();
        Map<String, Object> body = Map.of("name", "Asha", "visitType", "NEW", "consentVersion", "v1");
        String url = "/api/public/queues/" + single + "/tokens";

        String first = read(joinWithHeader(url, key, body).andExpect(status().isCreated()), "$.publicCode");
        String retry = read(
                joinWithHeader(url, key, body)
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.displayCode").value("A-001")),
                "$.publicCode");
        assertThat(retry).isEqualTo(first);

        // The key in the body works the same way.
        UUID bodyKey = UUID.randomUUID();
        publicPost(url, joinBody("Ravi", bodyKey)).andExpect(status().isCreated());
        publicPost(url, joinBody("Ravi", bodyKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayCode").value("A-002"));

        staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                .andExpect(jsonPath("$.waiting[*].displayCode", contains("A-001", "A-002")));
    }

    /** J6: reception closes the day; open tokens expire and patients see the queue is closed. */
    @Test
    void j6DayClose() throws Exception {
        openDay(single);
        String code = read(
                publicPost("/api/public/queues/" + single + "/tokens", joinBody("Asha", UUID.randomUUID())),
                "$.publicCode");
        walkIn(single, "Called one", "NORMAL");
        callNext(doctor1, single).andExpect(jsonPath("$.displayCode").value("A-001"));

        staffPost(receptionist, "/api/staff/queues/" + single + "/days/today/close")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        token(code)
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.queueStatus").value("CLOSED"));
        staffGet(receptionist, "/api/staff/queues/" + single + "/board")
                .andExpect(jsonPath("$.waiting", empty()))
                .andExpect(jsonPath("$.called", empty()))
                .andExpect(jsonPath("$.finished[*].status", contains("EXPIRED", "EXPIRED")));
        publicGet("/api/public/queues/" + clinic.slug() + "/dr-sharma")
                .andExpect(jsonPath("$.status").value("CLOSED"));
        publicPost("/api/public/queues/" + single + "/tokens", joinBody("Late", UUID.randomUUID()))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("https://baari.app/problems/queue-closed"))
                .andExpect(jsonPath("$.detail").value("This queue is closed for today."));
    }

    /**
     * J7: two doctors share a POOL queue; each call-next gives the next patient to the doctor who
     * called. (ETA projections for two servers come with the ETA work in Phase 4.)
     */
    @Test
    void j7PoolQueue() throws Exception {
        openDay(pool);
        walkIn(pool, "P1", "NORMAL");
        walkIn(pool, "P2", "NORMAL");
        walkIn(pool, "P3", "NORMAL");

        callNext(doctor1, pool)
                .andExpect(jsonPath("$.displayCode").value("B-001"))
                .andExpect(
                        jsonPath("$.servedByDoctorId").value(doctor1.doctorId().toString()));
        callNext(doctor2, pool)
                .andExpect(jsonPath("$.displayCode").value("B-002"))
                .andExpect(
                        jsonPath("$.servedByDoctorId").value(doctor2.doctorId().toString()));
        // Reception calling for a pool queue must say for which doctor.
        callNext(receptionist, pool)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://baari.app/problems/invalid-request"));

        ResultActions board = staffGet(receptionist, "/api/staff/queues/" + pool + "/board");
        board.andExpect(jsonPath("$.called[*].displayCode", contains("B-001", "B-002")))
                .andExpect(jsonPath("$.waiting[*].displayCode", contains("B-003")));
        List<String> doctors = read(board, "$.called[*].servedByDoctorId");
        assertThat(doctors)
                .containsExactly(
                        doctor1.doctorId().toString(), doctor2.doctorId().toString());
    }
}
