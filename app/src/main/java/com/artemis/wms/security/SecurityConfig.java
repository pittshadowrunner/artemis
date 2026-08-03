package com.artemis.wms.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * "IdP authenticates, app authorizes."
 * Default chain: OIDC login via per-tenant dynamic client registration.
 * `local-auth` profile: password login against app_user — exists in
 * dev/test, absent in production.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean
    @Profile("local-auth")
    public UserDetailsService localUsers(JdbcTemplate jdbc) {
        return username -> {
            var rows = jdbc.queryForList(
                "SELECT email::text AS email, password_hash FROM app_user WHERE email = ?::citext AND active AND password_hash IS NOT NULL",
                username);
            if (rows.isEmpty()) throw new UsernameNotFoundException(username);
            var r = rows.get(0);
            return User.withUsername((String) r.get("email"))
                    .password((String) r.get("password_hash"))
                    .authorities("ROLE_USER")
                    .build();
        };
    }

    @Bean
    @Profile("local-auth")
    public SecurityFilterChain localChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api-docs/**", "/actuator/health").permitAll()
                .anyRequest().authenticated())
            .httpBasic(b -> {})
            .addFilterAfter(tenantFilter,
                org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    @Profile("!local-auth")
    public SecurityFilterChain oidcChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(a -> a
                .requestMatchers("/api-docs/**", "/actuator/health", "/login/**", "/oauth2/**").permitAll()
                .anyRequest().authenticated())
            .oauth2Login(o -> {})
            .addFilterAfter(tenantFilter,
                org.springframework.security.web.authentication.AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
