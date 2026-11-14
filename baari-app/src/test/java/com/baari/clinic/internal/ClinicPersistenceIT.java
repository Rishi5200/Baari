package com.baari.clinic.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.QrMode;
import com.baari.clinic.QueueMode;
import com.baari.clinic.StaffRole;
import com.baari.common.Ids;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

/** V2 schema + JPA mappings against real Postgres. Each test rolls back. */
@Transactional
class ClinicPersistenceIT extends AbstractIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-11-14T04:30:00Z");

    @Autowired
    ClinicRepository clinics;

    @Autowired
    DoctorRepository doctors;

    @Autowired
    ServiceQueueRepository queues;

    @Autowired
    StaffUserRepository staff;

    @Autowired
    EntityManager em;

    private ClinicEntity newClinic() {
        String slug = "c-" + UUID.randomUUID();
        return clinics.saveAndFlush(
                new ClinicEntity(Ids.newId(), slug, "Clinic " + slug, ZoneId.of("Asia/Kolkata"), T0));
    }

    private ServiceQueueEntity queue(UUID clinicId, String slug) {
        return new ServiceQueueEntity(
                Ids.newId(), clinicId, slug, "Queue", QueueMode.POOL, QrMode.STATIC, "A", "hash", new byte[] {1, 2, 3});
    }

    @Test
    void entitiesRoundTripThroughPostgres() {
        ClinicEntity clinic = newClinic();
        DoctorEntity d1 = doctors.save(new DoctorEntity(Ids.newId(), clinic.getId(), "Dr. Sharma", "Room 1"));
        DoctorEntity d2 = doctors.save(new DoctorEntity(Ids.newId(), clinic.getId(), "Dr. Kaur", null));
        ServiceQueueEntity q = queue(clinic.getId(), "general");
        q.assignDoctors(Set.of(d1.getId(), d2.getId()));
        queues.save(q);
        staff.save(new StaffUserEntity(
                Ids.newId(), clinic.getId(), "doc-" + clinic.getSlug(), "hash", StaffRole.DOCTOR, d1.getId(), T0));
        em.flush();
        em.clear();

        assertThat(clinics.findById(clinic.getId()).orElseThrow().toView().timezone())
                .isEqualTo(ZoneId.of("Asia/Kolkata"));

        ServiceQueueEntity loaded = queues.findById(q.getId()).orElseThrow();
        assertThat(loaded.toView().doctorIds()).containsExactlyInAnyOrder(d1.getId(), d2.getId());
        assertThat(loaded.getQrSecret()).containsExactly(1, 2, 3);

        StaffUserEntity user = staff.findByUsername("doc-" + clinic.getSlug()).orElseThrow();
        assertThat(user.getRole()).isEqualTo(StaffRole.DOCTOR);
        assertThat(user.getDoctorId()).isEqualTo(d1.getId());

        assertThat(doctors.findAllByClinicIdOrderByName(clinic.getId()))
                .extracting(d -> d.toView().name())
                .containsExactly("Dr. Kaur", "Dr. Sharma");
    }

    @Test
    void clinicScopedLookupsDoNotSeeOtherClinics() {
        ClinicEntity a = newClinic();
        ClinicEntity b = newClinic();
        DoctorEntity doctorOfA = doctors.saveAndFlush(new DoctorEntity(Ids.newId(), a.getId(), "Dr. A", null));
        ServiceQueueEntity queueOfA = queues.saveAndFlush(queue(a.getId(), "general"));
        StaffUserEntity userOfA = staff.saveAndFlush(new StaffUserEntity(
                Ids.newId(), a.getId(), "rec-" + a.getSlug(), "hash", StaffRole.RECEPTIONIST, null, T0));

        assertThat(doctors.findByIdAndClinicId(doctorOfA.getId(), a.getId())).isPresent();
        assertThat(doctors.findByIdAndClinicId(doctorOfA.getId(), b.getId())).isEmpty();
        assertThat(queues.findByIdAndClinicId(queueOfA.getId(), b.getId())).isEmpty();
        assertThat(staff.findByIdAndClinicId(userOfA.getId(), b.getId())).isEmpty();
        assertThat(doctors.findAllByClinicIdOrderByName(b.getId())).isEmpty();
        assertThat(doctors.countByClinicIdAndIdIn(b.getId(), Set.of(doctorOfA.getId())))
                .isZero();
    }

    @Test
    void queueSlugIsUniquePerClinicOnly() {
        ClinicEntity a = newClinic();
        ClinicEntity b = newClinic();
        queues.saveAndFlush(queue(a.getId(), "general"));
        queues.saveAndFlush(queue(b.getId(), "general")); // same slug, other clinic: fine

        assertThat(queues.existsByClinicIdAndSlug(a.getId(), "general")).isTrue();
        assertThatThrownBy(() -> queues.saveAndFlush(queue(a.getId(), "general")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void usernameIsGloballyUnique() {
        ClinicEntity a = newClinic();
        ClinicEntity b = newClinic();
        String username = "same-" + a.getSlug();
        staff.saveAndFlush(
                new StaffUserEntity(Ids.newId(), a.getId(), username, "hash", StaffRole.CLINIC_ADMIN, null, T0));

        assertThatThrownBy(() -> staff.saveAndFlush(new StaffUserEntity(
                        Ids.newId(), b.getId(), username, "hash", StaffRole.CLINIC_ADMIN, null, T0)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void doctorMustReferenceExistingClinic() {
        assertThatThrownBy(() -> doctors.saveAndFlush(new DoctorEntity(Ids.newId(), UUID.randomUUID(), "Ghost", null)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
