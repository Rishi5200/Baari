package com.baari.clinic.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/** Scoped by clinic for the same reason as {@link DoctorRepository}. */
public interface ServiceQueueRepository extends JpaRepository<ServiceQueueEntity, UUID> {

    Optional<ServiceQueueEntity> findByIdAndClinicId(UUID id, UUID clinicId);

    List<ServiceQueueEntity> findAllByClinicIdOrderBySlug(UUID clinicId);

    boolean existsByClinicIdAndSlug(UUID clinicId, String slug);

    Optional<ServiceQueueEntity> findByClinicIdAndSlug(UUID clinicId, String slug);
}
