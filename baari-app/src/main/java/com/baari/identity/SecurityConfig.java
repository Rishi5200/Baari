package com.baari.identity;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Staff authentication (PLAN §9.1): form login, server-side sessions in Redis (Spring Session),
 * CSRF on, session id changed at login (fixation protection, Spring's default), POST logout.
 * Why sessions and not JWT: see docs/adr/0007-sessions-over-jwt.md.
 *
 * <p>URL rules only separate "public" from "logged in". Which role may do what is decided in the
 * services ({@code Permissions}), so the rule holds no matter which controller calls it.
 */
@Configuration
class SecurityConfig {

    @Bean
    SecurityFilterChain staffSecurity(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.requestMatchers("/login", "/error", "/css/**", "/js/**", "/favicon.ico")
                        .permitAll()
                        // Prometheus scrapes without credentials; locked down in Phase 8 hardening.
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/prometheus")
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .formLogin(form -> form.loginPage("/login").defaultSuccessUrl("/", true))
                .logout(logout -> logout.logoutSuccessUrl("/login?logout"));
        return http.build();
    }
}
