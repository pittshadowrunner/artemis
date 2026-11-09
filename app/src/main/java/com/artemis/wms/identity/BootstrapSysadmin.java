package com.artemis.wms.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * First boot only: with an empty user table and BOOTSTRAP_SYSADMIN_* set, create
 * the platform sysadmin. No hard-coded admin/admin anywhere. After that, users
 * are created through the API or arrive via IdP JIT provisioning.
 */
@Configuration
public class BootstrapSysadmin {
    private static final Logger log = LoggerFactory.getLogger(BootstrapSysadmin.class);

    @Bean
    public ApplicationRunner bootstrapSysadminRunner(JdbcTemplate jdbc, PasswordEncoder encoder,
                                               @Value("${artemis.bootstrap.sysadmin-email:}") String email,
                                               @Value("${artemis.bootstrap.sysadmin-password:}") String password) {
        return args -> {
            Integer users = jdbc.queryForObject("SELECT count(*) FROM app_user", Integer.class);
            if (users != null && users > 0) return;
            if (email.isBlank() || password.isBlank()) {
                log.warn("No users exist and BOOTSTRAP_SYSADMIN_EMAIL/PASSWORD are unset — nobody can log in.");
                return;
            }
            jdbc.update("""
                INSERT INTO app_user (email, display_name, password_hash, email_verified, sysadmin, break_glass)
                VALUES (?, 'Platform Sysadmin', ?, true, true, true)""", email, encoder.encode(password));
            log.info("Bootstrapped platform sysadmin {}", email);
        };
    }
}
