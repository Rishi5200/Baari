package com.baari.queue.api;

import com.baari.queue.IssuedToken;
import com.baari.queue.JoinRequest;
import com.baari.queue.Priority;
import com.baari.queue.PublicQueueView;
import com.baari.queue.PublicTokenView;
import com.baari.queue.QueueQueryService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.TokenStatus;
import com.baari.queue.VisitType;
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
 * Patient-facing API (PLAN Appendix A). No login: the queue id comes from the QR code and the
 * public code in the token link is the patient's key. Per-IP rate limits apply (ADR 0014), and
 * queues in rotating-QR mode need {@code qrWindow} and {@code qrSig} from the TV's current link.
 */
@RestController
@RequestMapping("/api/public")
class PublicQueueApi {

    private final QueueQueryService queries;
    private final TokenService tokens;
    private final TokenActionService actions;

    PublicQueueApi(QueueQueryService queries, TokenService tokens, TokenActionService actions) {
        this.queries = queries;
        this.tokens = tokens;
        this.actions = actions;
    }

    @GetMapping("/queues/{clinicSlug}/{queueSlug}")
    PublicQueueView queue(@PathVariable String clinicSlug, @PathVariable String queueSlug) {
        return queries.publicQueue(clinicSlug, queueSlug);
    }

    /**
     * 201 with the new token, or 200 with the existing one when the idempotency key (body field or
     * {@code Idempotency-Key} header; the body wins) was used before.
     */
    @PostMapping("/queues/{queueId}/tokens")
    ResponseEntity<JoinResponse> join(
            @PathVariable UUID queueId,
            @RequestBody JoinBody body,
            @RequestHeader(name = "Idempotency-Key", required = false) UUID headerKey) {
        UUID key = body.idempotencyKey() != null ? body.idempotencyKey() : headerKey;
        IssuedToken issued = tokens.join(
                queueId,
                new JoinRequest(
                        body.name(),
                        body.phone(),
                        body.visitType(),
                        body.consentVersion(),
                        key,
                        body.qrWindow(),
                        body.qrSig()));
        return ResponseEntity.status(issued.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(JoinResponse.of(issued));
    }

    @GetMapping("/tokens/{publicCode}")
    PublicTokenView token(@PathVariable String publicCode) {
        return queries.publicToken(publicCode);
    }

    @PostMapping("/tokens/{publicCode}/cancel")
    PublicTokenView cancel(@PathVariable String publicCode) {
        actions.cancelByPatient(publicCode);
        return queries.publicToken(publicCode);
    }

    /** The join form. {@code qrWindow}/{@code qrSig} come from the TV's link for rotating QR queues. */
    record JoinBody(
            String name,
            String phone,
            VisitType visitType,
            String consentVersion,
            Long qrWindow,
            String qrSig,
            UUID idempotencyKey) {

        @Override
        public String toString() {
            return "JoinBody[visitType=" + visitType + "]";
        }
    }

    /** @param eta always null until the ETA work in Phase 4 */
    record JoinResponse(
            String publicCode,
            String displayCode,
            TokenStatus status,
            Priority priority,
            Integer position,
            Object eta) {

        static JoinResponse of(IssuedToken issued) {
            return new JoinResponse(
                    issued.publicCode(),
                    issued.displayCode(),
                    issued.status(),
                    issued.priority(),
                    issued.position(),
                    null);
        }
    }
}
