package com.baari.web;

import com.baari.common.Secrets;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security headers that Spring Security does not set itself (PLAN §9.5). Spring Security already
 * sends {@code X-Content-Type-Options}, {@code X-Frame-Options: DENY}, no-cache for authenticated
 * pages and HSTS on HTTPS.
 *
 * <p>The Content Security Policy allows scripts, styles, images and connections from this site only.
 * The three pages that hand translated texts to their script do it in a small inline block; each
 * response carries a fresh random nonce ({@code cspNonce} request attribute) and only a script tag
 * with that nonce may run, so an injected script cannot.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
class SecurityHeadersFilter extends OncePerRequestFilter {

    static final String NONCE_ATTRIBUTE = "cspNonce";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String nonce = Secrets.randomToken(16);
        request.setAttribute(NONCE_ATTRIBUTE, nonce);
        response.setHeader(
                "Content-Security-Policy",
                "default-src 'self'; script-src 'self' 'nonce-" + nonce + "'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'self'; "
                        + "form-action 'self'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
        // the app needs none of these; a compromised page could not use them either
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=()");
        chain.doFilter(request, response);
    }
}
