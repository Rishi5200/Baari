package com.baari.clinic.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.DoctorView;
import com.baari.clinic.QueueMode;
import com.baari.clinic.ServiceQueueView;
import com.baari.clinic.StaffRole;
import com.baari.common.Secrets;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

/** The dev profile seeds the demo clinic from PLAN §12 Phase 1, exactly once. */
@ActiveProfiles("dev")
class DevSeedIT extends AbstractIntegrationTest {

    @Autowired
    DevSeed seed;

    @Autowired
    DevSeedProperties props;

    @Autowired
    ClinicRepository clinics;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    ServiceQueueRepository queues;

    @Autowired
    StaffUserRepository staff;

    @Autowired
    PasswordEncoder passwordEncoder;

    private UUID clinicId() {
        return clinics.findBySlug(DevSeed.CLINIC_SLUG).orElseThrow().getId();
    }

    @Test
    void seedsSharmaClinicWithDoctorsAndQueues() {
        var clinic = clinics.findBySlug(DevSeed.CLINIC_SLUG).orElseThrow().toView();
        assertThat(clinic.name()).isEqualTo("Sharma Clinic, Chandigarh");
        assertThat(clinic.timezone()).isEqualTo(ZoneId.of("Asia/Kolkata"));

        var seededDoctors = doctors.findAllByClinicIdOrderByName(clinic.id()).stream()
                .map(DoctorEntity::toView)
                .toList();
        assertThat(seededDoctors).extracting(DoctorView::name).containsExactly("Dr. Neha Gill", "Dr. Rajesh Sharma");
        UUID sharma = seededDoctors.get(1).id();

        var seededQueues = queues.findAllByClinicIdOrderBySlug(clinic.id()).stream()
                .map(ServiceQueueEntity::toView)
                .toList();
        assertThat(seededQueues)
                .extracting(ServiceQueueView::slug, ServiceQueueView::mode)
                .containsExactly(tuple("dr-sharma", QueueMode.SINGLE), tuple("general", QueueMode.POOL));
        assertThat(seededQueues.get(0).doctorIds()).containsExactly(sharma);
        assertThat(seededQueues.get(1).doctorIds()).hasSize(2);
        assertThat(queues.findAllByClinicIdOrderBySlug(clinic.id()))
                .allSatisfy(q -> assertThat(q.getDisplayKeyHash()).isEqualTo(Secrets.sha256Hex(props.displayKey())));
    }

    @Test
    void seedsOneLoginPerRoleWithPasswordsFromConfig() {
        assertLogin(props.platformAdmin(), StaffRole.PLATFORM_ADMIN, null);
        assertLogin(props.clinicAdmin(), StaffRole.CLINIC_ADMIN, clinicId());
        assertLogin(props.doctor(), StaffRole.DOCTOR, clinicId());
        assertLogin(props.receptionist(), StaffRole.RECEPTIONIST, clinicId());

        var doctorUser = staff.findByUsername(props.doctor().username()).orElseThrow();
        assertThat(doctors.findByIdAndClinicId(doctorUser.getDoctorId(), clinicId()))
                .get()
                .extracting(d -> d.toView().name())
                .isEqualTo("Dr. Rajesh Sharma");
    }

    @Test
    void runningTheSeedAgainChangesNothing() {
        long clinicCount = clinics.count();
        long doctorCount = doctors.count();
        long queueCount = queues.count();
        long staffCount = staff.count();

        seed.run(null);

        assertThat(clinics.count()).isEqualTo(clinicCount);
        assertThat(doctors.count()).isEqualTo(doctorCount);
        assertThat(queues.count()).isEqualTo(queueCount);
        assertThat(staff.count()).isEqualTo(staffCount);
    }

    private void assertLogin(DevSeedProperties.Login login, StaffRole role, UUID clinicId) {
        var user = staff.findByUsername(login.username()).orElseThrow();
        assertThat(user.getRole()).isEqualTo(role);
        assertThat(user.getClinicId()).isEqualTo(clinicId);
        assertThat(passwordEncoder.matches(login.password(), user.getPasswordHash()))
                .isTrue();
    }
}
