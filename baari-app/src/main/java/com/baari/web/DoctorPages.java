package com.baari.web;

import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorDetails;
import com.baari.clinic.DoctorView;
import com.baari.clinic.StaffPrincipal;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Doctor list and forms. All rules live in {@link DoctorAdminService}; this only maps HTTP. */
@Controller
@RequestMapping("/admin/doctors")
class DoctorPages {

    private final DoctorAdminService doctors;

    DoctorPages(DoctorAdminService doctors) {
        this.doctors = doctors;
    }

    @GetMapping
    String list(StaffPrincipal caller, Model model) {
        model.addAttribute("doctors", doctors.list(caller));
        return "admin/doctors";
    }

    @GetMapping("/new")
    String newForm(Model model) {
        return form(model, null, new DoctorDetails("", ""));
    }

    @PostMapping
    String create(
            StaffPrincipal caller,
            @Valid @ModelAttribute("form") DoctorDetails form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("errors", FormErrors.of(result));
            return form(model, null, form);
        }
        doctors.create(caller, form);
        redirect.addFlashAttribute("flash", "Doctor added.");
        return "redirect:/admin/doctors";
    }

    @GetMapping("/{id}/edit")
    String editForm(StaffPrincipal caller, @PathVariable UUID id, Model model) {
        DoctorView doctor = doctors.get(caller, id);
        return form(model, doctor.id(), new DoctorDetails(doctor.name(), doctor.roomLabel()));
    }

    @PostMapping("/{id}")
    String update(
            StaffPrincipal caller,
            @PathVariable UUID id,
            @Valid @ModelAttribute("form") DoctorDetails form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("errors", FormErrors.of(result));
            return form(model, id, form);
        }
        doctors.update(caller, id, form);
        redirect.addFlashAttribute("flash", "Doctor saved.");
        return "redirect:/admin/doctors";
    }

    @PostMapping("/{id}/active")
    String setActive(
            StaffPrincipal caller, @PathVariable UUID id, @RequestParam boolean active, RedirectAttributes redirect) {
        doctors.setActive(caller, id, active);
        redirect.addFlashAttribute("flash", active ? "Doctor activated." : "Doctor deactivated.");
        return "redirect:/admin/doctors";
    }

    private static String form(Model model, UUID doctorId, DoctorDetails form) {
        model.addAttribute("doctorId", doctorId);
        model.addAttribute("form", form);
        return "admin/doctor-form";
    }
}
