package com.baari.web;

import com.baari.clinic.StaffPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/** Landing page after login. The admin pages hang off it later in Phase 1. */
@Controller
class HomeController {

    @GetMapping("/")
    String home(StaffPrincipal caller, Model model) {
        model.addAttribute("caller", caller);
        return "home";
    }
}
