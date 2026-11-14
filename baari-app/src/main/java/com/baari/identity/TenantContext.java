package com.baari.identity;

import com.baari.clinic.StaffPrincipal;
import com.baari.identity.internal.StaffUserDetails;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves the logged-in staff member (clinic, role, doctor) from the authenticated session (PLAN
 * §9.2). This is the only source of {@code clinicId} for staff requests: never a URL or form field.
 *
 * <p>Controllers don't call this directly. They declare a {@link StaffPrincipal} parameter and
 * {@code StaffPrincipalArgumentResolver} fills it in, so the web module needn't depend on identity.
 */
@Component
public class TenantContext {

    public StaffPrincipal current() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof StaffUserDetails details) {
            return details.principal();
        }
        // Only reachable if an endpoint that asks for a StaffPrincipal is wrongly left public.
        throw new AuthenticationCredentialsNotFoundException("No staff user is logged in");
    }
}
