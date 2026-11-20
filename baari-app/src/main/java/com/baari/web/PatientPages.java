package com.baari.web;

import com.baari.common.DomainException;
import com.baari.common.Ids;
import com.baari.eta.EtaService;
import com.baari.eta.PatientTokenView;
import com.baari.queue.IssuedToken;
import com.baari.queue.JoinRequest;
import com.baari.queue.PublicQueueView;
import com.baari.queue.QueueDayStatus;
import com.baari.queue.QueueQueryService;
import com.baari.queue.TokenActionService;
import com.baari.queue.TokenService;
import com.baari.queue.VisitType;
import jakarta.validation.ConstraintViolationException;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The patient's two pages (PLAN §2.2): the join page behind the QR code and the token page behind
 * the link they get after joining. No login; the queue slugs and the secret public code are the
 * only keys. Pages are plain HTML forms that work without JavaScript; the token page adds live
 * updates on top.
 *
 * <p>The join form carries an idempotency key made when the page was rendered, so a double tap or
 * a retry after a flaky connection returns the same token (PLAN §2.3 J5).
 */
@Controller
class PatientPages {

    /** Version of the privacy notice shown on the join page; stored with the token as the consent. */
    static final String CONSENT_VERSION = "v1";

    private final QueueQueryService queries;
    private final TokenService tokens;
    private final TokenActionService actions;

    private final EtaService eta;

    PatientPages(QueueQueryService queries, TokenService tokens, TokenActionService actions, EtaService eta) {
        this.eta = eta;
        this.queries = queries;
        this.tokens = tokens;
        this.actions = actions;
    }

    @GetMapping("/j/{clinicSlug}/{queueSlug}")
    String joinPage(@PathVariable String clinicSlug, @PathVariable String queueSlug, Model model) {
        return renderJoin(clinicSlug, queueSlug, "", "", VisitType.NEW, Ids.newId(), model);
    }

    @PostMapping("/j/{clinicSlug}/{queueSlug}")
    String join(
            @PathVariable String clinicSlug,
            @PathVariable String queueSlug,
            @RequestParam(defaultValue = "") String name,
            @RequestParam(defaultValue = "") String phone,
            @RequestParam(defaultValue = "NEW") VisitType visitType,
            @RequestParam(required = false) String consent,
            @RequestParam UUID idempotencyKey,
            Model model) {
        PublicQueueView queue = queries.publicQueue(clinicSlug, queueSlug);
        if (consent == null) {
            model.addAttribute("formErrorKey", "join.error.consent");
            return renderJoin(clinicSlug, queueSlug, name, phone, visitType, idempotencyKey, model);
        }
        try {
            IssuedToken issued = tokens.join(
                    queue.queueId(),
                    new JoinRequest(name, phone.isBlank() ? null : phone, visitType, CONSENT_VERSION, idempotencyKey));
            return "redirect:/t/" + issued.publicCode();
        } catch (DomainException e) {
            // Paused, closed, full, already in the queue, bad phone number: tell the patient on the form.
            model.addAttribute("formError", e.getMessage());
        } catch (ConstraintViolationException e) {
            model.addAttribute("formErrorKey", "join.error.invalid");
        }
        return renderJoin(clinicSlug, queueSlug, name, phone, visitType, idempotencyKey, model);
    }

    private String renderJoin(
            String clinicSlug,
            String queueSlug,
            String name,
            String phone,
            VisitType visitType,
            UUID idempotencyKey,
            Model model) {
        PublicQueueView queue = queries.publicQueue(clinicSlug, queueSlug);
        model.addAttribute("queue", queue);
        model.addAttribute("clinicSlug", clinicSlug);
        model.addAttribute("queueSlug", queueSlug);
        model.addAttribute("name", name);
        model.addAttribute("phone", phone);
        model.addAttribute("visitType", visitType);
        model.addAttribute("idempotencyKey", idempotencyKey);
        model.addAttribute("canJoin", queue.status() == QueueDayStatus.OPEN);
        return "patient/join";
    }

    @GetMapping("/t/{publicCode}")
    String tokenPage(@PathVariable String publicCode, Model model) {
        PatientTokenView patient = eta.patientView(publicCode);
        model.addAttribute("view", patient.token());
        model.addAttribute("eta", patient.eta());
        model.addAttribute("publicCode", publicCode);
        return "patient/token";
    }

    @PostMapping("/t/{publicCode}/cancel")
    String cancel(@PathVariable String publicCode) {
        try {
            actions.cancelByPatient(publicCode);
        } catch (DomainException e) {
            // Already served, cancelled or expired (409): the token page shows the real state.
        }
        return "redirect:/t/" + publicCode;
    }
}
