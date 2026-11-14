package com.baari.clinic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.internal.ServiceQueueRepository;
import com.baari.clinic.internal.StaffUserRepository;
import com.baari.common.DuplicateException;
import com.baari.common.ForbiddenException;
import com.baari.common.InvalidRequestException;
import com.baari.common.NotFoundException;
import com.baari.common.Secrets;
import jakarta.validation.ConstraintViolationException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin services against real Postgres: role rules (403), tenant isolation (404) and the
 * business rules. HTTP-level checks follow once login exists. Each test rolls back.
 */
@Transactional
class ClinicAdminServicesIT extends AbstractIntegrationTest {

    private static final StaffPrincipal PLATFORM =
            new StaffPrincipal(UUID.randomUUID(), "platform", StaffRole.PLATFORM_ADMIN, null, null);

    @Autowired
    ClinicAdminService clinicAdmin;

    @Autowired
    DoctorAdminService doctorAdmin;

    @Autowired
    QueueAdminService queueAdmin;

    @Autowired
    StaffAdminService staffAdmin;

    @Autowired
    StaffUserRepository staffRepository;

    @Autowired
    ServiceQueueRepository queueRepository;

    @Autowired
    PasswordEncoder passwordEncoder;

    // Clinic A with one doctor and principals for every role; clinic B with its own admin.
    ClinicView clinicA;
    ClinicView clinicB;
    StaffPrincipal adminA;
    StaffPrincipal receptionistA;
    StaffPrincipal doctorUserA;
    StaffPrincipal adminB;
    DoctorView doctorA;

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private ClinicView newClinic(String adminUsername) {
        return clinicAdmin.createClinic(
                PLATFORM, new NewClinic(unique("clinic"), "Clinic", "Asia/Kolkata", adminUsername, "s3cret-pass"));
    }

    private static StaffPrincipal principal(StaffRole role, UUID clinicId, UUID doctorId) {
        return new StaffPrincipal(UUID.randomUUID(), "u", role, clinicId, doctorId);
    }

    private static QueueDetails pool(Set<UUID> doctorIds) {
        return new QueueDetails("General", QueueMode.POOL, QrMode.STATIC, "A", doctorIds);
    }

    @BeforeEach
    void setUp() {
        clinicA = newClinic(unique("admin-a"));
        clinicB = newClinic(unique("admin-b"));
        adminA = principal(StaffRole.CLINIC_ADMIN, clinicA.id(), null);
        adminB = principal(StaffRole.CLINIC_ADMIN, clinicB.id(), null);
        doctorA = doctorAdmin.create(adminA, new DoctorDetails("Dr. Sharma", "Room 1"));
        receptionistA = principal(StaffRole.RECEPTIONIST, clinicA.id(), null);
        doctorUserA = principal(StaffRole.DOCTOR, clinicA.id(), doctorA.id());
    }

    @Nested
    class Clinics {

        @Test
        void createClinicAlsoCreatesHashedClinicAdmin() {
            String username = unique("owner");
            ClinicView clinic = newClinic(username);

            var admin = staffRepository.findByUsername(username).orElseThrow();
            assertThat(admin.getClinicId()).isEqualTo(clinic.id());
            assertThat(admin.getRole()).isEqualTo(StaffRole.CLINIC_ADMIN);
            assertThat(admin.getPasswordHash()).isNotEqualTo("s3cret-pass").startsWith("$2");
            assertThat(passwordEncoder.matches("s3cret-pass", admin.getPasswordHash()))
                    .isTrue();
        }

        @Test
        void onlyPlatformAdminCreatesClinics() {
            var request = new NewClinic(unique("c"), "C", "Asia/Kolkata", unique("x"), "s3cret-pass");

            assertThatThrownBy(() -> clinicAdmin.createClinic(adminA, request)).isInstanceOf(ForbiddenException.class);
            assertThatThrownBy(() -> clinicAdmin.listClinics(receptionistA)).isInstanceOf(ForbiddenException.class);
        }

        @Test
        void rejectsDuplicateSlugUnknownZoneAndBadInput() {
            assertThatThrownBy(() -> clinicAdmin.createClinic(
                            PLATFORM, new NewClinic(clinicA.slug(), "C", "Asia/Kolkata", unique("x"), "s3cret-pass")))
                    .isInstanceOf(DuplicateException.class);
            assertThatThrownBy(() -> clinicAdmin.createClinic(
                            PLATFORM, new NewClinic(unique("c"), "C", "Mars/Olympus", unique("x"), "s3cret-pass")))
                    .isInstanceOf(InvalidRequestException.class);
            // Bean Validation runs on the service itself, not only in controllers.
            assertThatThrownBy(() -> clinicAdmin.createClinic(
                            PLATFORM, new NewClinic("Not A Slug", "C", "Asia/Kolkata", unique("x"), "short")))
                    .isInstanceOf(ConstraintViolationException.class);
        }

        @Test
        void staffSeeOnlyTheirOwnClinic() {
            assertThat(clinicAdmin.myClinic(receptionistA).id()).isEqualTo(clinicA.id());
            assertThat(clinicAdmin.myClinic(adminB).id()).isEqualTo(clinicB.id());
        }
    }

    @Nested
    class RoleRules {

        @Test
        void receptionistAndDoctorCannotManageDoctors() {
            var details = new DoctorDetails("Dr. New", null);
            for (StaffPrincipal p : new StaffPrincipal[] {receptionistA, doctorUserA, PLATFORM}) {
                assertThatThrownBy(() -> doctorAdmin.create(p, details)).isInstanceOf(ForbiddenException.class);
                assertThatThrownBy(() -> doctorAdmin.update(p, doctorA.id(), details))
                        .isInstanceOf(ForbiddenException.class);
                assertThatThrownBy(() -> doctorAdmin.setActive(p, doctorA.id(), false))
                        .isInstanceOf(ForbiddenException.class);
            }
        }

        @Test
        void receptionistAndDoctorCannotManageStaff() {
            var request = new NewStaffUser(unique("rec"), "s3cret-pass", StaffRole.RECEPTIONIST, null);
            for (StaffPrincipal p : new StaffPrincipal[] {receptionistA, doctorUserA, PLATFORM}) {
                assertThatThrownBy(() -> staffAdmin.create(p, request)).isInstanceOf(ForbiddenException.class);
                assertThatThrownBy(() -> staffAdmin.list(p)).isInstanceOf(ForbiddenException.class);
            }
        }

        @Test
        void receptionistAndDoctorCannotManageQueues() {
            for (StaffPrincipal p : new StaffPrincipal[] {receptionistA, doctorUserA}) {
                assertThatThrownBy(() -> queueAdmin.create(p, unique("q"), pool(Set.of())))
                        .isInstanceOf(ForbiddenException.class);
            }
        }

        @Test
        void allClinicStaffCanReadDoctorsAndQueues() {
            queueAdmin.create(adminA, "general", pool(Set.of(doctorA.id())));

            for (StaffPrincipal p : new StaffPrincipal[] {adminA, receptionistA, doctorUserA}) {
                assertThat(doctorAdmin.list(p)).extracting(DoctorView::id).containsExactly(doctorA.id());
                assertThat(queueAdmin.list(p))
                        .extracting(ServiceQueueView::slug)
                        .containsExactly("general");
            }
        }
    }

    @Nested
    class TenantIsolation {

        @Test
        void otherClinicsDoctorIsNotFound() {
            assertThatThrownBy(() -> doctorAdmin.get(adminB, doctorA.id())).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> doctorAdmin.update(adminB, doctorA.id(), new DoctorDetails("Hijacked", null)))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> doctorAdmin.setActive(adminB, doctorA.id(), false))
                    .isInstanceOf(NotFoundException.class);
            assertThat(doctorAdmin.list(adminB)).isEmpty();
        }

        @Test
        void otherClinicsQueueIsNotFound() {
            UUID queueA =
                    queueAdmin.create(adminA, "general", pool(Set.of())).queue().id();

            assertThatThrownBy(() -> queueAdmin.get(adminB, queueA)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> queueAdmin.update(adminB, queueA, pool(Set.of())))
                    .isInstanceOf(NotFoundException.class);
            assertThat(queueAdmin.list(adminB)).isEmpty();
        }

        @Test
        void otherClinicsStaffUserIsNotFound() {
            UUID userA = staffAdmin
                    .create(adminA, new NewStaffUser(unique("rec"), "s3cret-pass", StaffRole.RECEPTIONIST, null))
                    .id();

            assertThatThrownBy(() -> staffAdmin.get(adminB, userA)).isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> staffAdmin.setEnabled(adminB, userA, false))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> staffAdmin.resetPassword(adminB, userA, "new-pass-123"))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> staffAdmin.changeRole(adminB, userA, StaffRole.CLINIC_ADMIN, null))
                    .isInstanceOf(NotFoundException.class);
        }

        @Test
        void cannotLinkAnotherClinicsDoctor() {
            assertThatThrownBy(() -> queueAdmin.create(adminB, "general", pool(Set.of(doctorA.id()))))
                    .isInstanceOf(NotFoundException.class);
            assertThatThrownBy(() -> staffAdmin.create(
                            adminB, new NewStaffUser(unique("doc"), "s3cret-pass", StaffRole.DOCTOR, doctorA.id())))
                    .isInstanceOf(NotFoundException.class);
        }
    }

    @Nested
    class Queues {

        @Test
        void displayKeyIsReturnedOnceAndOnlyItsHashIsStored() {
            CreatedQueue created = queueAdmin.create(adminA, "dr-sharma", pool(Set.of(doctorA.id())));

            var stored = queueRepository.findById(created.queue().id()).orElseThrow();
            assertThat(stored.getDisplayKeyHash())
                    .isEqualTo(Secrets.sha256Hex(created.displayKey()))
                    .isNotEqualTo(created.displayKey());
            assertThat(stored.getQrSecret()).hasSize(32);
            assertThat(created.queue().doctorIds()).containsExactly(doctorA.id());
        }

        @Test
        void singleQueueHasAtMostOneDoctor() {
            DoctorView second = doctorAdmin.create(adminA, new DoctorDetails("Dr. Kaur", null));
            var details =
                    new QueueDetails("Dr", QueueMode.SINGLE, QrMode.STATIC, "A", Set.of(doctorA.id(), second.id()));

            assertThatThrownBy(() -> queueAdmin.create(adminA, "dr", details))
                    .isInstanceOf(InvalidRequestException.class);
        }

        @Test
        void slugIsUniqueWithinClinic() {
            queueAdmin.create(adminA, "general", pool(Set.of()));

            assertThatThrownBy(() -> queueAdmin.create(adminA, "general", pool(Set.of())))
                    .isInstanceOf(DuplicateException.class);
            queueAdmin.create(adminB, "general", pool(Set.of())); // other clinic: fine
        }

        @Test
        void updateReplacesDetailsAndDoctors() {
            UUID id = queueAdmin
                    .create(adminA, "general", pool(Set.of(doctorA.id())))
                    .queue()
                    .id();

            ServiceQueueView updated = queueAdmin.update(
                    adminA, id, new QueueDetails("Walk-ins", QueueMode.POOL, QrMode.ROTATING, "B", Set.of()));

            assertThat(updated.name()).isEqualTo("Walk-ins");
            assertThat(updated.qrMode()).isEqualTo(QrMode.ROTATING);
            assertThat(updated.displayCodePrefix()).isEqualTo("B");
            assertThat(updated.doctorIds()).isEmpty();
        }
    }

    @Nested
    class Staff {

        @Test
        void clinicAdminCannotGrantPlatformAdmin() {
            assertThatThrownBy(() -> staffAdmin.create(
                            adminA, new NewStaffUser(unique("evil"), "s3cret-pass", StaffRole.PLATFORM_ADMIN, null)))
                    .isInstanceOf(ForbiddenException.class);
        }

        @Test
        void doctorLoginMustLinkToDoctorAndOthersMustNot() {
            assertThatThrownBy(() -> staffAdmin.create(
                            adminA, new NewStaffUser(unique("doc"), "s3cret-pass", StaffRole.DOCTOR, null)))
                    .isInstanceOf(InvalidRequestException.class);
            assertThatThrownBy(() -> staffAdmin.create(
                            adminA,
                            new NewStaffUser(unique("rec"), "s3cret-pass", StaffRole.RECEPTIONIST, doctorA.id())))
                    .isInstanceOf(InvalidRequestException.class);

            StaffUserView doc = staffAdmin.create(
                    adminA, new NewStaffUser(unique("doc"), "s3cret-pass", StaffRole.DOCTOR, doctorA.id()));
            assertThat(doc.doctorId()).isEqualTo(doctorA.id());
        }

        @Test
        void usernameIsUniqueAcrossClinics() {
            String username = unique("rec");
            staffAdmin.create(adminA, new NewStaffUser(username, "s3cret-pass", StaffRole.RECEPTIONIST, null));

            assertThatThrownBy(() -> staffAdmin.create(
                            adminB, new NewStaffUser(username, "s3cret-pass", StaffRole.RECEPTIONIST, null)))
                    .isInstanceOf(DuplicateException.class);
        }

        @Test
        void changeRoleDisableAndResetPassword() {
            UUID id = staffAdmin
                    .create(adminA, new NewStaffUser(unique("rec"), "s3cret-pass", StaffRole.RECEPTIONIST, null))
                    .id();

            assertThat(staffAdmin
                            .changeRole(adminA, id, StaffRole.DOCTOR, doctorA.id())
                            .role())
                    .isEqualTo(StaffRole.DOCTOR);
            assertThat(staffAdmin.setEnabled(adminA, id, false).enabled()).isFalse();

            staffAdmin.resetPassword(adminA, id, "brand-new-pass");
            assertThat(passwordEncoder.matches(
                            "brand-new-pass",
                            staffRepository.findById(id).orElseThrow().getPasswordHash()))
                    .isTrue();
        }
    }
}
