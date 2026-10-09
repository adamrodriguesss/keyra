package com.rodrigues.keyra.service;

import com.rodrigues.keyra.config.KeyraProperties;
import com.rodrigues.keyra.entity.Manager;
import com.rodrigues.keyra.entity.ManagerRole;
import com.rodrigues.keyra.repository.ManagerRepository;
import com.rodrigues.keyra.security.PasswordPolicy;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * Creates the first admin from {@code KEYRA_BOOTSTRAP_ADMIN_*} when the instance has no managers yet.
 * Does nothing once any manager exists, so changing the env vars later has no effect.
 */
@Service
public class BootstrapAdminService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapAdminService.class);

    private final ManagerRepository managers;
    private final PasswordEncoder passwordEncoder;
    private final KeyraProperties.Bootstrap bootstrap;
    private final Clock clock;

    public BootstrapAdminService(
            ManagerRepository managers, PasswordEncoder passwordEncoder, KeyraProperties properties, Clock clock) {
        this.managers = managers;
        this.passwordEncoder = passwordEncoder;
        this.bootstrap = properties.bootstrap();
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (managers.count() > 0) {
            return;
        }
        String email = bootstrap.adminEmail();
        String password = bootstrap.adminPassword();
        if (isBlank(email) || isBlank(password)) {
            log.warn("No managers exist and KEYRA_BOOTSTRAP_ADMIN_EMAIL/PASSWORD are not set. "
                    + "Nobody can administer this instance.");
            return;
        }
        if (!PasswordPolicy.isValidLength(password)) {
            throw new IllegalStateException("KEYRA_BOOTSTRAP_ADMIN_PASSWORD must be " + PasswordPolicy.MIN_LENGTH
                    + " to " + PasswordPolicy.MAX_LENGTH + " characters");
        }
        try {
            managers.saveAndFlush(
                    new Manager(email, passwordEncoder.encode(password), ManagerRole.ADMIN, clock.instant()));
            log.info("Created bootstrap admin account");
        } catch (DataIntegrityViolationException e) {
            // Another replica created it first.
            log.info("Bootstrap admin already created by another instance");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
