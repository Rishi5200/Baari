package com.baari.queue;

import com.baari.clinic.QueueDirectory;
import com.baari.clinic.QueueInfo;
import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import com.baari.common.ForbiddenException;
import com.baari.common.NotFoundException;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Who may run a queue (PLAN §9.2): any staff member of the queue's clinic, except that a DOCTOR
 * may only act on queues they are assigned to. A queue of another clinic, or one a doctor isn't
 * assigned to, is "not found", so ids can't be probed. The platform admin runs no queues (403).
 */
@Component
class QueueAccess {

    private final QueueDirectory directory;

    QueueAccess(QueueDirectory directory) {
        this.directory = directory;
    }

    QueueInfo requireStaff(StaffPrincipal caller, UUID queueId) {
        if (!caller.role().belongsToClinic() || caller.clinicId() == null) {
            throw new ForbiddenException();
        }
        QueueInfo info = directory.find(queueId).orElseThrow(() -> new NotFoundException("Queue"));
        if (!info.queue().clinicId().equals(caller.clinicId())) {
            throw new NotFoundException("Queue");
        }
        if (caller.role() == StaffRole.DOCTOR
                && (caller.doctorId() == null || !info.queue().doctorIds().contains(caller.doctorId()))) {
            throw new NotFoundException("Queue");
        }
        return info;
    }
}
