package com.artemis.wms.identity;

import com.artemis.wms.tenancy.TenantFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * "IdP authenticates, app authorizes."
 *   default      → OIDC login (per-tenant registrations)
 *   local-auth   → password login against app_user; dev/test/LAN beta only
 * Authorization is never here: capabilities resolve per org node in {@link AccessService}.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    private static final String[] PUBLIC = {"/api-docs/**", "/actuator/health/**", "/login", "/error", "/css/**", "/js/**"};

    @Bean public PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(); }

    @Bean @Profile("local-auth")
    public UserDetailsService localUsers(JdbcTemplate jdbc) {
        return username -> {
            var rows = jdbc.queryForList(
                "SELECT email::text AS email, password_hash FROM app_user WHERE email = ?::citext AND active AND password_hash IS NOT NULL",
                username);
            if (rows.isEmpty()) throw new UsernameNotFoundException(username);
            return User.withUsername((String) rows.get(0).get("email"))
                .password((String) rows.get(0).get("password_hash")).authorities("ROLE_USER").build();
        };
    }

    @Bean @Profile("local-auth")
    public SecurityFilterChain localChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(a -> a.requestMatchers(PUBLIC).permitAll().anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .formLogin(f -> f.defaultSuccessUrl("/", false))
            .logout(l -> l.logoutSuccessUrl("/login?logout"))
            .addFilterAfter(tenantFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }

    @Bean @Profile("!local-auth")
    public SecurityFilterChain oidcChain(HttpSecurity http, TenantFilter tenantFilter) throws Exception {
        http.csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
            .authorizeHttpRequests(a -> a.requestMatchers(PUBLIC).permitAll()
                .requestMatchers("/login/**", "/oauth2/**").permitAll().anyRequest().authenticated())
            .oauth2Login(Customizer.withDefaults())
            .addFilterAfter(tenantFilter, AnonymousAuthenticationFilter.class);
        return http.build();
    }
}
