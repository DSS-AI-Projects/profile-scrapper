package com.profilescraper;

import com.vaadin.flow.component.page.AppShellConfigurator;
import com.vaadin.flow.component.page.Push;
import com.vaadin.flow.shared.communication.PushMode;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.servlet.support.SpringBootServletInitializer;

/**
 * Spring Boot + JMIX Flow UI application entry point.
 *
 * <p>Implements {@link AppShellConfigurator} so that the {@link Push} annotation
 * is picked up by Vaadin — this enables WebSocket server push, which is required
 * for background threads to update the UI via {@code ui.access()} immediately
 * (without waiting for the next HTTP request from the browser).</p>
 *
 * <p>Run with: {@code mvn spring-boot:run}
 * <br>Access web UI at: <a href="http://localhost:8080">http://localhost:8080</a></p>
 */
@Push(PushMode.AUTOMATIC)
@SpringBootApplication
public class ProfileScraperApplication
        extends SpringBootServletInitializer
        implements AppShellConfigurator {

    public static void main(String[] args) {
        org.springframework.boot.SpringApplication.run(ProfileScraperApplication.class, args);
    }
}
