package com.integration.camel.platform;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Wires the two shared configuration layers into every service:
 * <ol>
 *   <li>config/global/monitoring.yml, added last so everything else overrides it;</li>
 *   <li>overrides stored in the management console, fetched once at start-up and placed
 *       just below environment variables. If the console is unreachable the service starts
 *       on its own configuration (fail-open), and says so in the log.</li>
 * </ol>
 */
public class PlatformEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    static final String GLOBAL_SOURCE = "platformGlobalMonitoring";
    static final String CONSOLE_SOURCE = "platformConsoleOverrides";

    private final Log log;

    public PlatformEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.log = logFactory.getLog(PlatformEnvironmentPostProcessor.class);
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        addGlobalDefaults(environment);
        addConsoleOverrides(environment);
    }

    private void addGlobalDefaults(ConfigurableEnvironment environment) {
        ClassPathResource resource = new ClassPathResource("platform/monitoring.yml");
        if (!resource.exists() || environment.getPropertySources().contains(GLOBAL_SOURCE)) {
            return;
        }
        try {
            List<PropertySource<?>> loaded = new YamlPropertySourceLoader().load(GLOBAL_SOURCE, resource);
            loaded.forEach(environment.getPropertySources()::addLast);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read platform/monitoring.yml", e);
        }
    }

    private void addConsoleOverrides(ConfigurableEnvironment environment) {
        if (!environment.getProperty("platform.console.enabled", Boolean.class, true)) {
            return;
        }
        String service = environment.getProperty("spring.application.name");
        if (service == null || service.isBlank() || environment.getPropertySources().contains(CONSOLE_SOURCE)) {
            return;
        }
        ConsoleHttp http = ConsoleHttp.from(environment);
        Map<String, Object> overrides = http.fetchStartupOverrides(service, log);
        if (overrides.isEmpty()) {
            return;
        }
        MapPropertySource source = new MapPropertySource(CONSOLE_SOURCE, overrides);
        String anchor = StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME;
        if (environment.getPropertySources().contains(anchor)) {
            environment.getPropertySources().addAfter(anchor, source);
        } else {
            environment.getPropertySources().addFirst(source);
        }
    }

    @Override
    public int getOrder() {
        // After Spring Boot has loaded application.yml, so spring.application.name is known.
        return Ordered.LOWEST_PRECEDENCE;
    }
}
