package com.baari.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

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
                        // Actuator runs on the management port only (not forwarded by Caddy, ADR 0014);
                        // health for container probes, Prometheus for the scraper inside the network.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        // patients have no login; the public code in their link is the key
                        .requestMatchers("/api/public/**")
                        .permitAll()
                        // patient pages (join, token) and the TV: the queue id, public code or display
                        // key in the link is the credential; the services check it
                        .requestMatchers("/j/**", "/t/**", "/display/**", "/api/display/**")
                        .permitAll()
                        // queue work is for clinic staff; the platform admin runs no queues
                        .requestMatchers("/api/staff/**")
                        .hasAnyRole("CLINIC_ADMIN", "DOCTOR", "RECEPTIONIST")
                        // the queue console is for clinic staff
                        .requestMatchers("/staff/**")
                        .hasAnyRole("CLINIC_ADMIN", "DOCTOR", "RECEPTIONIST")
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
                // CSRF protects requests a browser sends with the staff session cookie. The public
                // API and the patient pages use no session, so there is nothing to ride on.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/public/**", "/j/**", "/t/**"))
                // Not logged in: API clients get a 401, pages redirect to the login page.
                .exceptionHandling(ex -> ex.authenticationEntryPoint(notLoggedIn()))
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }

    private static AuthenticationEntryPoint notLoggedIn() {
        RequestMatcher api = PathPatternRequestMatcher.withDefaults().matcher("/api/**");
        AuthenticationEntryPoint unauthorized = new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);
        AuthenticationEntryPoint loginPage = new LoginUrlAuthenticationEntryPoint("/login");
        return (request, response, exception) ->
                (api.matches(request) ? unauthorized : loginPage).commence(request, response, exception);
    }
}
