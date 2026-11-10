package com.baari;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/** Fails the build if a module reaches into another module's internals or creates a cycle. */
@Tag("unit")
class ModularityTest {

    @Test
    void moduleBoundariesAreRespected() {
        ApplicationModules.of(BaariApplication.class).verify();
    }
}
