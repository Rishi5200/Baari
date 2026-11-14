package com.baari.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Staff authentication (PLAN §9.1): form login, server-side sessions in Redis (Spring Session),
 * CSRF on, session id changed at login (fixation protection, Spring's default), POST logout.
 * Why sessions and not JWT: see docs/adr/0007-sessions-over-jwt.md.
 *
 * <p>Authorization happens twice, on purpose. The URL rules below stop a role at the door (a
 * receptionist can't even open the "new doctor" form). The services check again ({@code
 * Permissions}), so the rule still holds for any future caller that isn't one of these URLs.
 * Tenant isolation (another clinic's id → 404) is only in the services, because only they know
 * which clinic a resource belongs to.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain staffSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // public
                        .requestMatchers("/login", "/error", "/css/**", "/js/**", "/favicon.ico")
                        .permitAll()
                        // Prometheus scrapes without credentials; locked down in Phase 8 hardening.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        // platform admin only
                        .requestMatchers("/platform/**")
                        .hasRole("PLATFORM_ADMIN")
                        // every clinic role may look at the doctor and queue lists
                        .requestMatchers(HttpMethod.GET, "/admin/doctors", "/admin/queues")
                        .hasAnyRole("CLINIC_ADMIN", "DOCTOR", "RECEPTIONIST")
                        // everything else under /admin (forms, changes, staff) is for the clinic admin
                        .requestMatchers("/admin/**")
                        .hasRole("CLINIC_ADMIN")
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
