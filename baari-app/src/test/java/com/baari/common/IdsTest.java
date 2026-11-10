package com.baari.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("unit")
class IdsTest {

    @Test
    void generatesDistinctIds() {
        Set<UUID> ids = new HashSet<>();

        for (int i = 0; i < 10_000; i++) {
            ids.add(Ids.newId());
        }

        assertThat(ids).hasSize(10_000);
    }
}
