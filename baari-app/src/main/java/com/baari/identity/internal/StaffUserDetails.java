package com.baari.identity.internal;

import com.baari.clinic.StaffPrincipal;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

/**
 * Spring Security's view of a staff user. It wraps the {@link StaffPrincipal} that the rest of
 * the app uses, so the security types stay inside the identity module.
 *
 * <p>Implements {@link CredentialsContainer}: after a successful login Spring Security calls
 * {@link #eraseCredentials()}, so the password hash is never written into the Redis session.
 */
public final class StaffUserDetails implements UserDetails, CredentialsContainer {

    @Serial
    private static final long serialVersionUID = 1L;

    private final StaffPrincipal principal;
    private final List<GrantedAuthority> authorities;
    private final boolean enabled;
    private final boolean locked;
    private String passwordHash;

    public StaffUserDetails(StaffPrincipal principal, String passwordHash, boolean enabled, boolean locked) {
        this.principal = Objects.requireNonNull(principal, "principal");
        this.passwordHash = passwordHash;
        this.enabled = enabled;
        this.locked = locked;
        this.authorities =
                List.of(new SimpleGrantedAuthority("ROLE_" + principal.role().name()));
    }

    public StaffPrincipal principal() {
        return principal;
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return authorities;
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return principal.username();
    }

    @Override
    public boolean isAccountNonLocked() {
        return !locked;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void eraseCredentials() {
        passwordHash = null;
    }

    @Override
    public String toString() {
        return "StaffUserDetails[" + principal + "]";
    }
}
