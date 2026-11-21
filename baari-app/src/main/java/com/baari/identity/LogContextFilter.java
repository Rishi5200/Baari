package com.baari.identity;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the logged-in staff member's clinic and user id into the logging context (MDC), so every log
 * line of the request carries them, in the JSON logs too (PLAN Phase 8). Services add
 * {@code queueDayId} when they know it. The keys are removed when the request ends, because server
 * threads are reused. Ids only: never a name or phone number.
 *
 * <p>Runs just inside Spring Security's filter chain, where the session's user is known.
 */
@Component
@Order(LogContextFilter.ORDER)
class LogContextFilter extends OncePerRequestFilter {

    /** Spring Security's filter chain runs at order -100 (Boot's default); this runs right after it. */
    static final int ORDER = -99;

    static final String CLINIC_ID = "clinicId";
    static final String STAFF_USER_ID = "staffUserId";
    static final String QUEUE_DAY_ID = "queueDayId";

    private final TenantContext tenant;

    LogContextFilter(TenantContext tenant) {
        this.tenant = tenant;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            tenant.find().ifPresent(p -> {
                if (p.clinicId() != null) {
                    MDC.put(CLINIC_ID, p.clinicId().toString());
                }
                MDC.put(STAFF_USER_ID, p.userId().toString());
            });
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLINIC_ID);
            MDC.remove(STAFF_USER_ID);
            MDC.remove(QUEUE_DAY_ID);
        }
    }
}
