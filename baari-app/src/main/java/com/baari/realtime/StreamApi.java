package com.baari.realtime;

import com.baari.clinic.StaffPrincipal;
import com.baari.eta.EtaService;
import com.baari.queue.QueueQueryService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The three live streams (PLAN §10.1). Each sends complete {@code snapshot} events: first
 * immediately, then after every change to the queue. Commands are ordinary POSTs elsewhere.
 *
 * <p>Access is the same as for the matching plain GET: the patient's public code, the staff
 * session (clinic and role checked by the board query), or the TV display key.
 */
@RestController
class StreamApi {

    private final SseHub hub;
    private final QueueQueryService queries;
    private final EtaService eta;

    StreamApi(SseHub hub, QueueQueryService queries, EtaService eta) {
        this.eta = eta;
        this.hub = hub;
        this.queries = queries;
    }

    @GetMapping(path = "/api/public/tokens/{publicCode}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter patient(@PathVariable String publicCode, HttpServletResponse response) {
        noBuffering(response);
        UUID queueId = queries.queueIdOfToken(publicCode);
        return hub.subscribe(queueId, "patient", () -> eta.patientView(publicCode));
    }

    @GetMapping(path = "/api/staff/queues/{queueId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter staff(StaffPrincipal caller, @PathVariable UUID queueId, HttpServletResponse response) {
        noBuffering(response);
        return hub.subscribe(queueId, "staff", () -> eta.staffBoard(caller, queueId));
    }

    @GetMapping(path = "/api/display/queues/{queueId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter display(@PathVariable UUID queueId, @RequestParam String key, HttpServletResponse response) {
        noBuffering(response);
        response.setHeader("Referrer-Policy", "no-referrer");
        return hub.subscribe(queueId, "display", () -> queries.displayBoard(queueId, key));
    }

    /** Tells reverse proxies (nginx; Caddy ignores it) not to hold the stream back in a buffer. */
    private static void noBuffering(HttpServletResponse response) {
        response.setHeader("X-Accel-Buffering", "no");
        response.setHeader("Cache-Control", "no-cache");
    }
}
