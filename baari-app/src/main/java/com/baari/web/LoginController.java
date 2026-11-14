package com.baari.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Renders the staff login page. Spring Security handles the POST to /login itself. */
@Controller
class LoginController {

    @GetMapping("/login")
    String login() {
        return "login";
    }
}
