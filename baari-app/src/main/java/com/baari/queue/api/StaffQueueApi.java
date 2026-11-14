package com.baari.queue.api;

import com.baari.clinic.StaffPrincipal;
import com.baari.common.InvalidRequestException;
import com.baari.queue.Board;
import com.baari.queue.IssuedToken;
import com.baari.queue.Priority;
import com.baari.queue.QueueDayService;
import com.baari.queue.QueueDayView;
import com.baari.queue.QueueQueryService;
import com.baari.queue.StaffTokenView;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.WalkInRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Staff API (PLAN Appendix A): session login + CSRF. The clinic always comes from the logged-in
 * {@link StaffPrincipal}, never from the URL or body (PLAN §9.2); the services check it.
 */
@RestController
@RequestMapping("/api/staff")
class StaffQueueApi {

    private final QueueDayService days;
    private final QueueQueryService queries;
    private final TokenService tokens;
    private final TokenActionService actions;

    StaffQueueApi(QueueDayService days, QueueQueryService queries, TokenService tokens, TokenActionService actions) {
        this.days = days;
        this.queries = queries;
        this.tokens = tokens;
        this.actions = actions;
    }

    // --- queue day ---

    @PostMapping("/queues/{queueId}/days/today/open")
    QueueDayView open(StaffPrincipal caller, @PathVariable UUID queueId) {
        return days.open(caller, queueId);
    }

    @PostMapping("/queues/{queueId}/days/today/pause")
    QueueDayView pause(StaffPrincipal caller, @PathVariable UUID queueId) {
        return days.pause(caller, queueId);
    }

    @PostMapping("/queues/{queueId}/days/today/resume")
    QueueDayView resume(StaffPrincipal caller, @PathVariable UUID queueId) {
        return days.resume(caller, queueId);
    }

    @PostMapping("/queues/{queueId}/days/today/close")
    QueueDayView close(StaffPrincipal caller, @PathVariable UUID queueId) {
        return days.close(caller, queueId);
    }

    @GetMapping("/queues/{queueId}/board")
    Board board(StaffPrincipal caller, @PathVariable UUID queueId) {
        return queries.board(caller, queueId);
    }

    // --- tokens ---

    /** 201 with the new walk-in, or 200 when the idempotency key (body or header) was used before. */
    @PostMapping("/queues/{queueId}/tokens")
    ResponseEntity<IssuedToken> walkIn(
            StaffPrincipal caller,
            @PathVariable UUID queueId,
            @RequestBody WalkInRequest body,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID headerKey) {
        WalkInRequest request = body.idempotencyKey() != null || headerKey == null
                ? body
                : new WalkInRequest(body.name(), body.phone(), body.visitType(), body.priority(), headerKey);
        IssuedToken issued = tokens.issueWalkIn(caller, queueId, request);
        return ResponseEntity.status(issued.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(issued);
    }

    @PostMapping("/queues/{queueId}/call-next")
    StaffTokenView callNext(
            StaffPrincipal caller, @PathVariable UUID queueId, @RequestBody(required = false) DoctorBody body) {
        return actions.callNext(caller, queueId, body == null ? null : body.doctorId());
    }

    /** Call this patient out of order. */
    @PostMapping("/tokens/{tokenId}/call")
    StaffTokenView call(
            StaffPrincipal caller, @PathVariable UUID tokenId, @RequestBody(required = false) DoctorBody body) {
        return actions.call(caller, tokenId, body == null ? null : body.doctorId());
    }

    @PostMapping("/tokens/{tokenId}/start")
    StaffTokenView start(StaffPrincipal caller, @PathVariable UUID tokenId) {
        return actions.start(caller, tokenId);
    }

    @PostMapping("/tokens/{tokenId}/complete")
    StaffTokenView complete(StaffPrincipal caller, @PathVariable UUID tokenId) {
        return actions.complete(caller, tokenId);
    }

    @PostMapping("/tokens/{tokenId}/no-show")
    StaffTokenView noShow(
            StaffPrincipal caller, @PathVariable UUID tokenId, @RequestBody(required = false) NoShowBody body) {
        return actions.noShow(caller, tokenId, body != null && Boolean.TRUE.equals(body.force()));
    }

    @PostMapping("/tokens/{tokenId}/recall")
    StaffTokenView recall(StaffPrincipal caller, @PathVariable UUID tokenId) {
        return actions.recall(caller, tokenId);
    }

    @PostMapping("/tokens/{tokenId}/cancel")
    StaffTokenView cancel(StaffPrincipal caller, @PathVariable UUID tokenId) {
        return actions.cancel(caller, tokenId);
    }

    @PostMapping("/tokens/{tokenId}/priority")
    StaffTokenView priority(StaffPrincipal caller, @PathVariable UUID tokenId, @RequestBody PriorityBody body) {
        if (body.priority() == null) {
            throw new InvalidRequestException("priority is required.");
        }
        return actions.setPriority(caller, tokenId, body.priority());
    }

    /** @param doctorId which doctor is calling; needed for POOL queues unless the caller is a doctor */
    record DoctorBody(UUID doctorId) {}

    record NoShowBody(Boolean force) {}

    record PriorityBody(Priority priority) {}
}
