package com.integration.camel.platform;

import org.apache.camel.CamelContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import io.micrometer.core.instrument.MeterRegistry;

/** Registers the platform's shared beans in every service that depends on the starter. */
@AutoConfiguration
@EnableConfigurationProperties(PlatformProperties.class)
public class PlatformAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public CorrelationIdProcessor correlationIdProcessor(PlatformProperties properties) {
        return new CorrelationIdProcessor(properties.getCorrelation().getHeader());
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformRouteConfiguration platformRouteConfiguration(PlatformProperties properties,
                                                                 CorrelationIdProcessor correlation) {
        return new PlatformRouteConfiguration(properties, correlation);
    }

    /** Tags every metric with service, environment and domain so one Grafana dashboard serves all services. */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> platformCommonTags(Environment env, PlatformProperties properties) {
        return registry -> registry.config().commonTags(
                "service", env.getProperty("spring.application.name", "unnamed-service"),
                "environment", properties.getEnvironment(),
                "domain", properties.getDomain());
    }

    @Bean
    @ConditionalOnProperty(prefix = "platform.console", name = "enabled", havingValue = "true", matchIfMissing = true)
    public ConsoleAgent platformConsoleAgent(Environment env, PlatformProperties properties,
                                             CamelContext camelContext,
                                             ObjectProvider<BuildProperties> buildProperties) {
        String version = buildProperties.getIfAvailable() != null ? buildProperties.getIfAvailable().getVersion() : "dev";
        return new ConsoleAgent(env.getProperty("spring.application.name", "unnamed-service"), version, properties,
                ConsoleHttp.from(env), LoggingSystem.get(getClass().getClassLoader()), camelContext);
    }
}
