package com.baari.web;

import com.baari.clinic.QrMode;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Model attributes every page in the web module needs: the logged-in user for the navigation
 * bar, and the enum values for form drop-downs (Thymeleaf 3.1 restricts static {@code T(...)}
 * access in templates, so we hand them over explicitly).
 */
@ControllerAdvice(basePackages = "com.baari.web")
class WebModelAdvice {

    /** Roles a clinic admin may hand out; PLATFORM_ADMIN is never one of them. */
    private static final List<StaffRole> CLINIC_ROLES =
            Arrays.stream(StaffRole.values()).filter(StaffRole::belongsToClinic).toList();

    @ModelAttribute
    void addCommonAttributes(Optional<StaffPrincipal> caller, Model model) {
        caller.ifPresent(c -> {
            model.addAttribute("caller", c);
            model.addAttribute("isClinicAdmin", c.role() == StaffRole.CLINIC_ADMIN);
        });
        model.addAttribute("clinicRoles", CLINIC_ROLES);
        model.addAttribute("queueModes", QueueMode.values());
        model.addAttribute("qrModes", QrMode.values());
    }
}
