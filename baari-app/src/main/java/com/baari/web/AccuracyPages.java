package com.baari.web;

import com.baari.clinic.StaffPrincipal;
import com.baari.eta.AccuracyReport;
import com.baari.eta.MlTraining;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Clinic admin: how accurate the wait estimates have been, and the ML models (PLAN §8.7, Phase 7). */
@Controller
@RequestMapping("/admin/accuracy")
class AccuracyPages {

    private final AccuracyReport report;
    private final MlTraining training;

    AccuracyPages(AccuracyReport report, MlTraining training) {
        this.report = report;
        this.training = training;
    }

    @GetMapping
    String view(StaffPrincipal caller, Model model) {
        model.addAttribute("report", report.view(caller));
        return "admin/accuracy";
    }

    @PostMapping("/train")
    String trainNow(StaffPrincipal caller, RedirectAttributes redirect) {
        MlTraining.Result r = training.trainNow(caller);
        redirect.addFlashAttribute("flash", describe(r));
        return "redirect:/admin/accuracy";
    }

    private static String describe(MlTraining.Result r) {
        return switch (r.outcome()) {
            case TOO_FEW_SAMPLES ->
                "Not enough history yet: " + r.trainingSamples() + " finished consultations in the training window.";
            case PROMOTED ->
                String.format(
                        Locale.ROOT,
                        "New model %s promoted: wait error %.1f min against %.1f min for %s.",
                        r.best(),
                        r.bestWaitMaeMin(),
                        r.baselineWaitMaeMin(),
                        r.baseline());
            case KEPT ->
                String.format(
                        Locale.ROOT,
                        "Trained, but the best model (%s, %.1f min) did not beat %s (%.1f min) by 5%%; keeping %s.",
                        r.best(),
                        r.bestWaitMaeMin(),
                        r.baseline(),
                        r.baselineWaitMaeMin(),
                        r.baseline());
        };
    }
}
