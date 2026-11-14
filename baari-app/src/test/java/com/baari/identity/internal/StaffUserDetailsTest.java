package com.baari.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import com.baari.clinic.StaffPrincipal;
import com.baari.clinic.StaffRole;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;

@Tag("unit")
class StaffUserDetailsTest {

    private final StaffPrincipal principal =
            new StaffPrincipal(UUID.randomUUID(), "ravi", StaffRole.RECEPTIONIST, UUID.randomUUID(), null);

    @Test
    void roleBecomesSpringAuthority() {
        var details = new StaffUserDetails(principal, "$2a$hash", true, false);

        assertThat(details.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_RECEPTIONIST");
        assertThat(details.getUsername()).isEqualTo("ravi");
        assertThat(details.isAccountNonLocked()).isTrue();
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void lockedAndDisabledFlagsAreExposed() {
        var details = new StaffUserDetails(principal, "$2a$hash", false, true);

        assertThat(details.isAccountNonLocked()).isFalse();
        assertThat(details.isEnabled()).isFalse();
    }

    @Test
    void eraseCredentialsDropsHashBeforeSessionIsStored() {
        var details = new StaffUserDetails(principal, "$2a$hash", true, false);

        details.eraseCredentials();

        assertThat(details.getPassword()).isNull();
        assertThat(details.toString()).doesNotContain("$2a$hash");
    }

    @Test
    void usernamesAreNormalisedForLookupAndLockout() {
        assertThat(StaffUserDetailsService.normalise("  Ravi.K ")).isEqualTo("ravi.k");
        assertThat(StaffUserDetailsService.normalise(null)).isEmpty();
    }
}
