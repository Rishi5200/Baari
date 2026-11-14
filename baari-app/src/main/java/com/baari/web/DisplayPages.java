package com.baari.web;

import com.baari.queue.QueueQueryService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The waiting-room TV (PLAN §2.2 screen 5). The display key in the URL is the only credential, so
 * the page is not cached and the key is never sent on as a referrer. It shows display codes only.
 */
@Controller
class DisplayPages {

    private final QueueQueryService queries;

    DisplayPages(QueueQueryService queries) {
        this.queries = queries;
    }

    @GetMapping("/display/{queueId}")
    String display(@PathVariable UUID queueId, @RequestParam String key, HttpServletResponse response, Model model) {
        model.addAttribute("board", queries.displayBoard(queueId, key));
        model.addAttribute("queueId", queueId);
        model.addAttribute("key", key);
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cache-Control", "no-store");
        return "display/board";
    }
}
