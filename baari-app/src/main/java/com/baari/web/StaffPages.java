package com.baari.web;

import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.NewStaffUser;
import com.baari.clinic.StaffAdminService;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.DuplicateException;
import com.baari.common.InvalidRequestException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
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

/** Staff logins of the clinic. Clinic admin only (URL rule and service both enforce it). */
@Controller
@RequestMapping("/admin/staff")
class StaffPages {

    record RoleForm(@NotNull StaffRole role, UUID doctorId) {}

    record PasswordForm(@NotBlank @Size(min = 8, max = 72) String password) {
        @Override
        public String toString() {
            return "PasswordForm[***]";
        }
    }

    private final StaffAdminService staff;
    private final DoctorAdminService doctors;

    StaffPages(StaffAdminService staff, DoctorAdminService doctors) {
        this.staff = staff;
        this.doctors = doctors;
    }

    @GetMapping
    String list(StaffPrincipal caller, Model model) {
        model.addAttribute("users", staff.list(caller));
        return "admin/staff";
    }

    @GetMapping("/new")
    String newForm(StaffPrincipal caller, Model model) {
        return newUserForm(caller, model, new NewStaffUser("", "", StaffRole.RECEPTIONIST, null));
    }

    @PostMapping
    String create(
            StaffPrincipal caller,
            @Valid @ModelAttribute("form") NewStaffUser form,
            BindingResult result,
            Model model,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            model.addAttribute("errors", FormErrors.of(result));
            return newUserForm(caller, model, form);
        }
        try {
            staff.create(caller, form);
        } catch (DuplicateException | InvalidRequestException e) {
            model.addAttribute("formError", e.getMessage());
            return newUserForm(caller, model, form);
        }
        redirect.addFlashAttribute("flash", "Login created for " + form.username() + ".");
        return "redirect:/admin/staff";
    }

    @GetMapping("/{id}")
    String detail(StaffPrincipal caller, @PathVariable UUID id, Model model) {
        model.addAttribute("user", staff.get(caller, id));
        model.addAttribute("doctors", doctors.list(caller));
        return "admin/staff-detail";
    }

    @PostMapping("/{id}/role")
    String changeRole(
            StaffPrincipal caller,
            @PathVariable UUID id,
            @Valid @ModelAttribute RoleForm form,
            BindingResult result,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("flashError", "Choose a valid role.");
            return "redirect:/admin/staff/" + id;
        }
        try {
            staff.changeRole(caller, id, form.role(), form.doctorId());
            redirect.addFlashAttribute("flash", "Role changed.");
        } catch (InvalidRequestException e) {
            redirect.addFlashAttribute("flashError", e.getMessage());
        }
        return "redirect:/admin/staff/" + id;
    }

    @PostMapping("/{id}/password")
    String resetPassword(
            StaffPrincipal caller,
            @PathVariable UUID id,
            @Valid @ModelAttribute PasswordForm form,
            BindingResult result,
            RedirectAttributes redirect) {
        if (result.hasErrors()) {
            redirect.addFlashAttribute("flashError", "Password must be 8 to 72 characters.");
            return "redirect:/admin/staff/" + id;
        }
        staff.resetPassword(caller, id, form.password());
        redirect.addFlashAttribute("flash", "Password reset.");
        return "redirect:/admin/staff/" + id;
    }

    @PostMapping("/{id}/enabled")
    String setEnabled(
            StaffPrincipal caller, @PathVariable UUID id, @RequestParam boolean enabled, RedirectAttributes redirect) {
        staff.setEnabled(caller, id, enabled);
        redirect.addFlashAttribute("flash", enabled ? "Login enabled." : "Login disabled.");
        return "redirect:/admin/staff/" + id;
    }

    private String newUserForm(StaffPrincipal caller, Model model, NewStaffUser form) {
        // Never send a typed password back to the browser.
        model.addAttribute("form", new NewStaffUser(form.username(), "", form.role(), form.doctorId()));
        model.addAttribute("doctors", doctors.list(caller));
        return "admin/staff-form";
    }
}
