package com.baari.common;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;
import java.util.Objects;
import java.util.UUID;
import org.springframework.data.domain.Persistable;

/**
 * Base class for entities whose id is assigned by the application ({@link Ids#newId()}).
 *
 * <p>Why {@link Persistable}: Spring Data treats an entity with a non-null id as existing and calls
 * {@code merge}, which first runs a SELECT. With app-assigned ids every new entity has an id, so we
 * track "new" ourselves and get a plain INSERT.
 */
@MappedSuperclass
public abstract class AssignedIdEntity implements Persistable<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Transient
    private boolean isNew = true;

    protected AssignedIdEntity() {}

    protected AssignedIdEntity(UUID id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.isNew = false;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        return id != null && id.equals(((AssignedIdEntity) o).id);
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }
}
