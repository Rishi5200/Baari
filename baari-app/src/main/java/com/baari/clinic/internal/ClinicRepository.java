package com.baari.clinic.internal;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ClinicRepository extends JpaRepository<ClinicEntity, UUID> {

    Optional<ClinicEntity> findBySlug(String slug);

    boolean existsBySlug(String slug);
}
