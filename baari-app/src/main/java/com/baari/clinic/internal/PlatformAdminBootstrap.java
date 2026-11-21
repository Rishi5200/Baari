package com.baari.clinic.internal;

import com.baari.clinic.StaffRole;
import com.baari.common.Ids;
import java.time.Clock;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creates the first PLATFORM_ADMIN on a real server, where the dev seed must not run. No service
 * can create that role (PLAN §9.2), so without this a fresh production database has no login at
 * all. Set {@code BAARI_ADMIN_USERNAME} and {@code BAARI_ADMIN_PASSWORD} for the first start; the
 * account is created only if that username does not exist yet, so later starts change nothing (a
 * changed password in the environment is ignored). See ADR 0016.
 */
@Component
@ConditionalOnProperty("baari.bootstrap.admin-username")
class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final String username;
    private final String password;
    private final StaffUserRepository staff;
    private final PasswordEncoder passwordEncoder;
    private final TransactionTemplate tx;
    private final Clock clock;

    PlatformAdminBootstrap(
            @Value("${baari.bootstrap.admin-username}") String username,
            @Value("${baari.bootstrap.admin-password:}") String password,
            StaffUserRepository staff,
            PasswordEncoder passwordEncoder,
            TransactionTemplate tx,
            Clock clock) {
        this.username = username.strip().toLowerCase(Locale.ROOT);
        this.password = password;
        this.staff = staff;
        this.passwordEncoder = passwordEncoder;
        this.tx = tx;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username.isEmpty()) {
            return;
        }
        if (staff.existsByUsername(username)) {
            log.debug("Platform admin bootstrap: account already exists, nothing to do");
            return;
        }
        // Same limits as every other password (BCrypt reads at most 72 bytes). Failing the start is
        // better than creating an account the operator cannot log in to.
        if (password.length() < 8 || password.length() > 72) {
            throw new IllegalStateException("BAARI_ADMIN_PASSWORD must be 8 to 72 characters");
        }
        try {
            tx.executeWithoutResult(status -> staff.save(new StaffUserEntity(
                    Ids.newId(),
                    null,
                    username,
                    passwordEncoder.encode(password),
                    StaffRole.PLATFORM_ADMIN,
                    null,
                    clock.instant())));
            log.info("Platform admin bootstrap: created the platform admin account");
        } catch (DataIntegrityViolationException e) {
            // The other instance created it a moment ago (unique username): that is the goal.
            log.info("Platform admin bootstrap: account was created by another instance");
        }
    }
}
