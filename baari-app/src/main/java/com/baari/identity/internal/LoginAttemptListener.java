package com.baari.identity.internal;

import org.springframework.context.event.EventListener;
import org.springframework.security.authentication.event.AuthenticationFailureBadCredentialsEvent;
import org.springframework.security.authentication.event.AuthenticationSuccessEvent;
import org.springframework.stereotype.Component;

/**
 * Feeds Spring Security's login events into {@link LoginAttempts}. Only a wrong password (or
 * unknown user) counts as a failure; a login rejected because the account is already locked or
 * disabled does not extend the lock.
 */
@Component
class LoginAttemptListener {

    private final LoginAttempts loginAttempts;

    LoginAttemptListener(LoginAttempts loginAttempts) {
        this.loginAttempts = loginAttempts;
    }

    @EventListener
    void onBadCredentials(AuthenticationFailureBadCredentialsEvent event) {
        loginAttempts.recordFailure(
                StaffUserDetailsService.normalise(event.getAuthentication().getName()));
    }

    @EventListener
    void onSuccess(AuthenticationSuccessEvent event) {
        loginAttempts.recordSuccess(
                StaffUserDetailsService.normalise(event.getAuthentication().getName()));
    }
}
