package com.baari.web;

import com.baari.clinic.ClinicAdminService;
import com.baari.clinic.NewClinic;
import com.baari.clinic.StaffPrincipal;
import com.baari.common.DuplicateException;
import com.baari.common.InvalidRequestException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Platform admin: list clinics and onboard a new one with its first admin login. */
@Controller
@RequestMapping("/platform/clinics")
class ClinicPages {

    private final ClinicAdminService clinics;

    ClinicPages(ClinicAdminService clinics) {
        this.clinics = clinics;
    }

    @GetMapping
    String list(StaffPrincipal caller, Model model) {
        model.addAttribute("clinics", clinics.listClinics(caller));
        return "platform/clinics";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        return form(model, new NewClinic("", "", "Asia/Kolkata", "", ""));
    }

    @PostMapping
    String create(
            StaffPrincipal caller,
            @Valid @ModelAttribute("form") NewClinic form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("errors", FormErrors.of(result));
            return form(model, form);
        }
        try {
            clinics.createClinic(caller, form);
        } catch (DuplicateException | InvalidRequestException e) {
            model.addAttribute("formError", e.getMessage());
            return form(model, form);
        }
        redirect.addFlashAttribute("flash", "Clinic " + form.name() + " created.");
        return "redirect:/platform/clinics";
    }

    private static String form(Model model, NewClinic form) {
        // Never send a typed password back to the browser.
        model.addAttribute("form", new NewClinic(form.slug(), form.name(), form.timezone(), form.adminUsername(), ""));
        return "platform/clinic-form";
    }
}
