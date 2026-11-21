package com.baari.clinic.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baari.AbstractIntegrationTest;
import com.baari.clinic.StaffRole;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/** A fresh production database gets its first platform admin from the environment, once. */
@TestPropertySource(
        properties = {"baari.bootstrap.admin-username=  Ops-Admin ", "baari.bootstrap.admin-password=first-start-pass"})
class PlatformAdminBootstrapIT extends AbstractIntegrationTest {

    @Autowired
    PlatformAdminBootstrap bootstrap;

    @Autowired
    StaffUserRepository staff;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Test
    void createsThePlatformAdminAtStartupWithALowercaseUsername() {
        var admin = staff.findByUsername("ops-admin").orElseThrow();
        assertThat(admin.getRole()).isEqualTo(StaffRole.PLATFORM_ADMIN);
        assertThat(admin.getClinicId()).isNull();
        assertThat(passwordEncoder.matches("first-start-pass", admin.getPasswordHash()))
                .isTrue();
    }

    @Test
    void laterStartsChangeNothingEvenWithAnotherPassword() {
        long before = staff.count();
        String hash = staff.findByUsername("ops-admin").orElseThrow().getPasswordHash();

        bootstrapWith("ops-admin", "a-different-pass").run(null);
        bootstrap.run(null);

        assertThat(staff.count()).isEqualTo(before);
        assertThat(staff.findByUsername("ops-admin").orElseThrow().getPasswordHash())
                .isEqualTo(hash);
    }

    @Test
    void refusesToStartWithAPasswordThatCouldNotBeUsed() {
        assertThatThrownBy(() -> bootstrapWith("second-admin", "short").run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("8 to 72");
        assertThat(staff.existsByUsername("second-admin")).isFalse();
    }

    @Test
    void anEmptyUsernameDoesNothing() {
        long before = staff.count();
        bootstrapWith(" ", "").run(null);
        assertThat(staff.count()).isEqualTo(before);
    }

    private PlatformAdminBootstrap bootstrapWith(String username, String password) {
        return new PlatformAdminBootstrap(username, password, staff, passwordEncoder, tx, clock);
    }
}
