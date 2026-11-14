package com.baari.clinic.internal;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Lookups are always scoped by clinic: a doctor of another clinic is simply "not found", which the
 * services turn into a 404 so we never reveal that it exists (PLAN §9.2).
 */
public interface DoctorRepository extends JpaRepository<DoctorEntity, UUID> {

    Optional<DoctorEntity> findByIdAndClinicId(UUID id, UUID clinicId);

    List<DoctorEntity> findAllByClinicIdOrderByName(UUID clinicId);

    long countByClinicIdAndIdIn(UUID clinicId, Collection<UUID> ids);
}
