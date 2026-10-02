package com.integration.camel.platform;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.camel.CamelContext;
import org.apache.camel.Route;
import org.apache.camel.ServiceStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * Background agent that, on every poll, pulls this service's settings from the console,
 * applies log level changes live, and reports a heartbeat with route states.
 * A log level removed in the console reverts to inheriting from its parent logger.
 *
 * It starts on ApplicationReadyEvent rather than as a SmartLifecycle bean: a lifecycle bean that
 * depends on the CamelContext makes Spring start Camel before the routes are collected.
 */
public class ConsoleAgent implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleAgent.class);

    private final String service;
    private final String version;
    private final PlatformProperties properties;
    private final ConsoleHttp http;
    private final LoggingSystem loggingSystem;
    private final CamelContext camelContext;
    private final long startedAt = System.currentTimeMillis();
    private final String instance = hostname();

    private final Map<String, String> appliedLevels = new HashMap<>();
    private long appliedVersion = -1;
    private ScheduledExecutorService scheduler;

    public ConsoleAgent(String service, String version, PlatformProperties properties, ConsoleHttp http,
                        LoggingSystem loggingSystem, CamelContext camelContext) {
        this.service = service;
        this.version = version;
        this.properties = properties;
        this.http = http;
        this.loggingSystem = loggingSystem;
        this.camelContext = camelContext;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "platform-console-agent");
            t.setDaemon(true);
            return t;
        });
        long period = properties.getConsole().getPollInterval().toMillis();
        scheduler.scheduleWithFixedDelay(this::tick, 0, period, TimeUnit.MILLISECONDS);
        LOG.info("Console agent started for {} (console {}, every {})", service,
                properties.getConsole().getUrl(), properties.getConsole().getPollInterval());
    }

    void tick() {
        try {
            http.fetchConfig(service).ifPresent(this::apply);
            http.sendHeartbeat(service, heartbeat("UP"));
        } catch (RuntimeException e) {
            LOG.warn("Console agent cycle failed: {}", e.toString());
        }
    }

    synchronized void apply(ConsoleConfig config) {
        if (config.version() == appliedVersion) {
            return;
        }
        Map<String, String> wanted = config.logLevels();
        for (String logger : Map.copyOf(appliedLevels).keySet()) {
            if (!wanted.containsKey(logger)) {
                loggingSystem.setLogLevel(loggerName(logger), null);
                appliedLevels.remove(logger);
                LOG.info("Log level for {} reset to inherited (removed in console)", logger);
            }
        }
        wanted.forEach((logger, level) -> {
            if (!level.equalsIgnoreCase(appliedLevels.get(logger))) {
                loggingSystem.setLogLevel(loggerName(logger), LogLevel.valueOf(level.toUpperCase()));
                appliedLevels.put(logger, level);
                LOG.info("Log level for {} set to {} from console", logger, level);
            }
        });
        if (appliedVersion >= 0) {
            LOG.info("Console config version {} applied; property changes take effect on restart", config.version());
        }
        appliedVersion = config.version();
    }

    Heartbeat heartbeat(String status) {
        List<Heartbeat.RouteState> routes = camelContext.getRoutes().stream()
                .map(this::routeState)
                .toList();
        return new Heartbeat(instance, properties.getEnvironment(), properties.getDomain(), version,
                camelContext.getVersion(), status, appliedVersion, startedAt, routes);
    }

    private Heartbeat.RouteState routeState(Route route) {
        ServiceStatus status = camelContext.getRouteController().getRouteStatus(route.getRouteId());
        return new Heartbeat.RouteState(route.getRouteId(),
                Optional.ofNullable(status).map(Enum::name).orElse("Unknown"),
                route.getEndpoint().getEndpointBaseUri());
    }

    private static String loggerName(String logger) {
        return "ROOT".equalsIgnoreCase(logger) ? LoggingSystem.ROOT_LOGGER_NAME : logger;
    }

    private static String hostname() {
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "unknown";
        }
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            http.sendHeartbeat(service, heartbeat("STOPPING"));
        }
    }
}
