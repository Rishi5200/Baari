package com.baari.web;

import com.baari.clinic.CreatedQueue;
import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorView;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.StaffPrincipal;
import com.baari.common.DuplicateException;
import com.baari.common.InvalidRequestException;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Queue list and forms. Rules live in {@link QueueAdminService}. */
@Controller
@RequestMapping("/admin/queues")
class QueuePages {

    private final QueueAdminService queues;
    private final DoctorAdminService doctors;

    QueuePages(QueueAdminService queues, DoctorAdminService doctors) {
        this.queues = queues;
        this.doctors = doctors;
    }

    @GetMapping
    String list(StaffPrincipal caller, Model model) {
        model.addAttribute("queues", queues.list(caller));
        Map<UUID, String> doctorNames =
                doctors.list(caller).stream().collect(Collectors.toMap(DoctorView::id, DoctorView::name));
        model.addAttribute("doctorNames", doctorNames);
        return "admin/queues";
    }

    @GetMapping("/new")
    String newForm(StaffPrincipal caller, Model model) {
        return form(caller, model, null, QueueForm.empty());
    }

    /**
     * Renders the result directly instead of redirecting: the TV display key is shown exactly once
     * and must not travel through a URL or a flash attribute stored in the session.
     */
    @PostMapping
    String create(
            StaffPrincipal caller, @Valid @ModelAttribute("form") QueueForm form, BindingResult result, Model model) {
        Map<String, String> errors = FormErrors.of(result);
        if (form.slug() == null || form.slug().isBlank()) {
            errors.putIfAbsent("slug", "must not be blank");
        }
        if (!errors.isEmpty()) {
            model.addAttribute("errors", errors);
            return form(caller, model, null, form);
        }
        try {
            CreatedQueue created = queues.create(caller, form.slug(), form.toDetails());
            model.addAttribute("created", created);
            return "admin/queue-created";
        } catch (DuplicateException | InvalidRequestException e) {
            model.addAttribute("formError", e.getMessage());
            return form(caller, model, null, form);
        }
    }

    @GetMapping("/{id}/edit")
    String editForm(StaffPrincipal caller, @PathVariable UUID id, Model model) {
        return form(caller, model, id, QueueForm.of(queues.get(caller, id)));
    }

    @PostMapping("/{id}")
    String update(
            StaffPrincipal caller,
            @PathVariable UUID id,
            @Valid @ModelAttribute("form") QueueForm form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("errors", FormErrors.of(result));
            return form(caller, model, id, form);
        }
        try {
            queues.update(caller, id, form.toDetails());
        } catch (InvalidRequestException e) {
            model.addAttribute("formError", e.getMessage());
            return form(caller, model, id, form);
        }
        redirect.addFlashAttribute("flash", "Queue saved.");
        return "redirect:/admin/queues";
    }

    private String form(StaffPrincipal caller, Model model, UUID queueId, QueueForm form) {
        model.addAttribute("queueId", queueId);
        // On edit the slug is fixed (it is printed on QR posters), so show the stored one.
        if (queueId != null) {
            String slug = queues.get(caller, queueId).slug();
            form = new QueueForm(
                    slug, form.name(), form.mode(), form.qrMode(), form.displayCodePrefix(), form.doctorIds());
        }
        model.addAttribute("form", form);
        model.addAttribute("doctors", doctors.list(caller));
        return "admin/queue-form";
    }
}
