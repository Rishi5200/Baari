package com.baari.web;

import com.baari.clinic.DoctorAdminService;
import com.baari.clinic.DoctorView;
import com.baari.clinic.QueueAdminService;
import com.baari.clinic.QueueMode;
import com.baari.clinic.ServiceQueueView;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.queue.QueueQueryService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * The queue console (PLAN §2.2 screen 4): the page shell only. The board itself is drawn in the
 * browser from the staff stream, and every button calls the staff REST API, so the page holds no
 * business logic.
 */
@Controller
@RequestMapping("/staff/queues")
class ConsolePages {

    private final QueueAdminService queues;
    private final DoctorAdminService doctors;
    private final QueueQueryService queries;

    ConsolePages(QueueAdminService queues, DoctorAdminService doctors, QueueQueryService queries) {
        this.queues = queues;
        this.doctors = doctors;
        this.queries = queries;
    }

    /** The queues this person can run: all of the clinic's, or for a doctor only their own. */
    @GetMapping
    String list(StaffPrincipal caller, Model model) {
        List<ServiceQueueView> mine = queues.list(caller).stream()
                .filter(q -> caller.role() != StaffRole.DOCTOR
                        || (caller.doctorId() != null && q.doctorIds().contains(caller.doctorId())))
                .toList();
        model.addAttribute("queues", mine);
        return "staff/queues";
    }

    @GetMapping("/{queueId}")
    String console(StaffPrincipal caller, @PathVariable UUID queueId, Model model) {
        // The board query is the access check (other clinic or unassigned doctor -> 404).
        queries.board(caller, queueId);
        ServiceQueueView queue = queues.get(caller, queueId);
        List<DoctorView> queueDoctors = doctors.list(caller).stream()
                .filter(d -> d.active() && queue.doctorIds().contains(d.id()))
                .toList();
        model.addAttribute("queue", queue);
        model.addAttribute("queueDoctors", queueDoctors);
        // Reception must say which doctor is calling on a pool; a doctor always calls for themselves.
        model.addAttribute("chooseDoctor", queue.mode() == QueueMode.POOL && caller.role() != StaffRole.DOCTOR);
        return "staff/console";
    }
}
