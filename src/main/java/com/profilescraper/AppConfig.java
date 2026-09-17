package com.profilescraper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

@Configuration
public class AppConfig {

    /**
     * Serves HTTP over Tomcat's NIO2 connector instead of the default NIO one.
     *
     * <p>Opt-in via {@code server.tomcat.nio2=true}; absent that property nothing here applies
     * and the server behaves exactly as before.
     *
     * <p>Exists for hosts where opening an NIO {@link java.nio.channels.Selector} fails. The JDK
     * implements a selector's wakeup pipe with a Unix-domain-socket loopback connect, and where
     * something in the network stack rejects that, Tomcat's default connector cannot start at
     * all ({@code Protocol handler start failed: Unable to establish loopback connection}). NIO2
     * uses IOCP on Windows rather than a selector, so it comes up on those machines.
     */
    @Bean
    @ConditionalOnProperty(name = "server.tomcat.nio2", havingValue = "true")
    public WebServerFactoryCustomizer<TomcatServletWebServerFactory> nio2Connector() {
        return factory -> factory.setProtocol("org.apache.coyote.http11.Http11Nio2Protocol");
    }

    @Bean(name = "scraperExecutor")
    public Executor scraperExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("scraper-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }
}