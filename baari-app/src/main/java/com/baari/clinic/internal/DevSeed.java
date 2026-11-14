package com.baari.clinic.internal;

import com.baari.clinic.QrMode;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffRole;
import com.baari.common.Ids;
import com.baari.common.Secrets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Dev profile only: creates the demo clinic from PLAN §12 Phase 1 at startup.
 *
 * <ul>
 *   <li>Sharma Clinic, Chandigarh (Asia/Kolkata) with Dr. Rajesh Sharma and Dr. Neha Gill
 *   <li>Queue {@code dr-sharma} (SINGLE, Dr. Sharma) and {@code general} (POOL, both doctors)
 *   <li>One login per role, credentials from {@code application-dev.yml}
 * </ul>
 *
 * Idempotent: if the clinic already exists nothing is changed, so restarting the app (or editing
 * the demo data by hand) is safe. Uses repositories directly because no service creates a
 * PLATFORM_ADMIN: that account only ever comes from a seed or a manual database step.
 */
@Component
@Profile("dev")
@EnableConfigurationProperties(DevSeedProperties.class)
class DevSeed implements ApplicationRunner {

    static final String CLINIC_SLUG = "sharma-clinic";

    private static final Logger log = LoggerFactory.getLogger(DevSeed.class);

    private final DevSeedProperties props;
    private final ClinicRepository clinics;
    private final DoctorRepository doctors;
    private final ServiceQueueRepository queues;
    private final StaffUserRepository staff;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    DevSeed(
            DevSeedProperties props,
            ClinicRepository clinics,
            DoctorRepository doctors,
            ServiceQueueRepository queues,
            StaffUserRepository staff,
            PasswordEncoder passwordEncoder,
            TransactionTemplate tx,
            Clock clock) {
        this.props = props;
        this.clinics = clinics;
        this.doctors = doctors;
        this.queues = queues;
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        tx.executeWithoutResult(status -> seed());
    }

    void seed() {
        Instant now = clock.instant();
        if (!staff.existsByUsername(props.platformAdmin().username())) {
            staff.save(user(null, props.platformAdmin(), StaffRole.PLATFORM_ADMIN, null, now));
        }
        if (clinics.existsBySlug(CLINIC_SLUG)) {
            log.debug("Dev seed: {} already exists, nothing to do", CLINIC_SLUG);
            return;
        }

        ClinicEntity clinic = clinics.save(new ClinicEntity(
                Ids.newId(), CLINIC_SLUG, "Sharma Clinic, Chandigarh", ZoneId.of("Asia/Kolkata"), now));
        UUID clinicId = clinic.getId();

        DoctorEntity sharma = doctors.save(new DoctorEntity(Ids.newId(), clinicId, "Dr. Rajesh Sharma", "Room 1"));
        DoctorEntity gill = doctors.save(new DoctorEntity(Ids.newId(), clinicId, "Dr. Neha Gill", "Room 2"));

        queues.save(queue(clinicId, "dr-sharma", "Dr. Sharma", QueueMode.SINGLE, "A", Set.of(sharma.getId())));
        queues.save(
                queue(clinicId, "general", "General OPD", QueueMode.POOL, "B", Set.of(sharma.getId(), gill.getId())));

        staff.save(user(clinicId, props.clinicAdmin(), StaffRole.CLINIC_ADMIN, null, now));
        staff.save(user(clinicId, props.doctor(), StaffRole.DOCTOR, sharma.getId(), now));
        staff.save(user(clinicId, props.receptionist(), StaffRole.RECEPTIONIST, null, now));

        log.info("Dev seed: created {} with 2 doctors, 2 queues and one login per role", CLINIC_SLUG);
    }

    private ServiceQueueEntity queue(
            UUID clinicId, String slug, String name, QueueMode mode, String prefix, Set<UUID> doctorIds) {
        ServiceQueueEntity queue = new ServiceQueueEntity(
                Ids.newId(),
                clinicId,
                slug,
                name,
                mode,
                QrMode.STATIC,
                prefix,
                Secrets.sha256Hex(props.displayKey()),
                Secrets.randomBytes(32));
        queue.assignDoctors(doctorIds);
        return queue;
    }

    private StaffUserEntity user(
            UUID clinicId, DevSeedProperties.Login login, StaffRole role, UUID doctorId, Instant now) {
        return new StaffUserEntity(
                Ids.newId(), clinicId, login.username(), passwordEncoder.encode(login.password()), role, doctorId, now);
    }
}
