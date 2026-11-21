package com.baari.clinic.internal;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

/**
 * {@link #findByUsername} is unscoped on purpose: it is the login lookup, which happens before we
 * know the clinic. Everything an admin does goes through the clinic-scoped methods.
 */
public interface StaffUserRepository extends JpaRepository<StaffUserEntity, UUID> {

    Optional<StaffUserEntity> findByUsername(String username);

    boolean existsByUsername(String username);

    Optional<StaffUserEntity> findByIdAndClinicId(UUID id, UUID clinicId);

    List<StaffUserEntity> findAllByClinicIdOrderByUsername(UUID clinicId);

    /**
     * The clinic's active admins, row-locked until the transaction ends. Two admins demoting each
     * other at the same moment are serialised here, so they cannot both pass the last-admin check.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<StaffUserEntity> findAllByClinicIdAndRoleAndEnabledTrue(UUID clinicId, com.baari.clinic.StaffRole role);
}
