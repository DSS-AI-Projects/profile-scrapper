package com.profilescraper.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Permissive security configuration for this internal developer tool.
 *
 * <p>JMIX's {@code CoreSecurityConfiguration} is {@code @Order(400)} and
 * redirects unauthenticated users to {@code /login}. By registering a
 * filter chain at {@code @Order(1)} (higher priority) that permits all
 * requests, we bypass JMIX's login wall entirely — appropriate for a
 * localhost-only recruitment scraper that has no multi-user requirements.</p>
 *
 * <p>Spring Security CSRF is disabled because Vaadin 24 manages its own
 * CSRF protection independently of Spring's filter chain.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    @Order(1)
    public SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(AbstractHttpConfigurer::disable)
                .build();
    }
}
