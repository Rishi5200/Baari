package com.baari.identity.internal;

import com.baari.clinic.StaffAccount;
import com.baari.clinic.StaffAccountLookup;
import com.baari.clinic.StaffPrincipal;
import java.util.Locale;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads a staff user for Spring Security's DaoAuthenticationProvider. A locked account (too many
 * failed logins) is returned as "not non-locked", so Spring rejects it <em>before</em> checking the
 * password: the right password doesn't help during the 15-minute lock.
 */
@Service
class StaffUserDetailsService implements UserDetailsService {

    private final StaffAccountLookup accounts;
    private final LoginAttempts loginAttempts;

    StaffUserDetailsService(StaffAccountLookup accounts, LoginAttempts loginAttempts) {
        this.accounts = accounts;
        this.loginAttempts = loginAttempts;
    }

    static String normalise(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    @Override
    public UserDetails loadUserByUsername(String rawUsername) {
        String username = normalise(rawUsername);
        StaffAccount account =
                accounts.findForLogin(username).orElseThrow(() -> new UsernameNotFoundException("unknown user"));
        return new StaffUserDetails(
                StaffPrincipal.of(account.user()),
                account.passwordHash(),
                account.user().enabled(),
                loginAttempts.isLocked(username));
    }
}
