package com.baari.clinic.internal;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * {@link #findByUsername} is unscoped on purpose: it is the login lookup, which happens before we
 * know the clinic. Everything an admin does goes through the clinic-scoped methods.
 */
public interface StaffUserRepository extends JpaRepository<StaffUserEntity, UUID> {

    Optional<StaffUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<StaffUserEntity> findByIdAndClinicId(UUID id, UUID clinicId);

    List<StaffUserEntity> findAllByClinicIdOrderByUsername(UUID clinicId);
}
