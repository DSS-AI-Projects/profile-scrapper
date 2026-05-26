package com.profilescraper.security;

import io.jmix.core.security.UserRepository;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * In-memory UserRepository that replaces JMIX's default database-backed store.
 *
 * <p>This app has no JPA entities and no database — Liquibase is disabled.
 * JMIX's {@code CoreSecurityConfiguration} requires a {@link UserRepository} bean
 * to authenticate users; without the DB the default implementation finds nothing
 * and every login attempt fails. This {@code @Primary} bean provides a single
 * hard-coded admin / admin user so the web UI is accessible immediately.</p>
 *
 * <p>Passwords use Spring Security's {@code {noop}} prefix (plain-text comparison)
 * which is safe for a local developer tool that is never exposed to the internet.</p>
 */
@Component
@Primary
public class InMemoryUserRepository implements UserRepository {

    /** The only real user: admin / admin. */
    private static final UserDetails ADMIN = User.builder()
            .username("admin")
            .password("{noop}admin")
            .roles("ADMIN")
            .build();

    /**
     * JMIX uses a "system" principal for internal background operations
     * (e.g. scheduled jobs, event listeners). We give it a random, non-guessable
     * password so it can never be used for interactive login.
     */
    private static final UserDetails SYSTEM = User.builder()
            .username("system")
            .password("{noop}" + UUID.randomUUID())
            .roles("SYSTEM")
            .build();

    /**
     * JMIX requires an anonymous principal for unauthenticated requests.
     * Empty password + empty authority list = no privileges.
     */
    private static final UserDetails ANONYMOUS = User.builder()
            .username("anonymous")
            .password("")
            .authorities(Collections.emptyList())
            .build();

    // ── UserDetailsService ────────────────────────────────────────────────────

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        if ("admin".equalsIgnoreCase(username)) {
            return ADMIN;
        }
        throw new UsernameNotFoundException("User not found: " + username);
    }

    // ── UserRepository ────────────────────────────────────────────────────────

    @Override
    public UserDetails getSystemUser() {
        return SYSTEM;
    }

    @Override
    public UserDetails getAnonymousUser() {
        return ANONYMOUS;
    }

    @Override
    public List<? extends UserDetails> getByUsernameLike(String username) {
        if (username == null || username.isBlank() || "admin".contains(username.toLowerCase())) {
            return List.of(ADMIN);
        }
        return List.of();
    }
}
