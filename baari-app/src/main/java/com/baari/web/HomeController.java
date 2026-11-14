package com.baari.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Landing page after login: links to the pages the caller's role can use. */
@Controller
class HomeController {

    @GetMapping("/")
    String home() {
        return "home";
    }
}
