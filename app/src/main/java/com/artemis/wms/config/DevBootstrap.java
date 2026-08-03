package com.artemis.wms.config;

import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.UUID;

/** local-auth only: seed a corporation + break-glass admin for dev/test. */
@Configuration
@Profile("local-auth")
public class DevBootstrap {

    @Bean
    public CommandLineRunner seedDevAdmin(JdbcTemplate jdbc, PasswordEncoder encoder) {
        return args -> {
            Integer existing = jdbc.queryForObject(
                "SELECT count(*) FROM app_user WHERE email = 'admin@artemis.local'", Integer.class);
            if (existing != null && existing > 0) return;

            UUID corpId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO org_node (org_node_id, corporation_id, level, code, name)
                VALUES (?, ?, 'CORPORATION', 'DEV', 'Dev Corporation')
                """, corpId, corpId);
            UUID userId = UUID.randomUUID();
            jdbc.update("""
                INSERT INTO app_user (user_id, email, password_hash, display_name, email_verified, account_source, break_glass)
                VALUES (?, 'admin@artemis.local', ?, 'Dev Admin', true, 'LOCAL', true)
                """, userId, encoder.encode("admin"));
            jdbc.update("""
                INSERT INTO user_org_grant (user_id, org_node_id, role_id)
                SELECT ?, ?, role_id FROM role WHERE code = 'ADMIN' AND corporation_id IS NULL
                """, userId, corpId);
        };
    }
}
