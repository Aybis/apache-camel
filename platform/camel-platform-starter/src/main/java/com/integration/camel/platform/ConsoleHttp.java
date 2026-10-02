package com.integration.camel.platform;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

import tools.jackson.databind.json.JsonMapper;

/**
 * Minimal client for the management console API. Every call fails open: an unreachable
 * console never stops a service from starting or processing messages.
 */
public class ConsoleHttp {

    private static final Logger LOG = LoggerFactory.getLogger(ConsoleHttp.class);
    private static final int STARTUP_ATTEMPTS = 3;

    private final String baseUrl;
    private final Duration timeout;
    private final HttpClient client;
    private final JsonMapper mapper = JsonMapper.builder().build();

    public ConsoleHttp(String baseUrl, Duration timeout) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .proxy(HttpClient.Builder.NO_PROXY)
                .build();
    }

    static ConsoleHttp from(Environment env) {
        return new ConsoleHttp(
                env.getProperty("platform.console.url", "http://localhost:8090"),
                env.getProperty("platform.console.timeout", Duration.class, Duration.ofSeconds(2)));
    }

    /** The service's stored settings, or empty when none exist or the console is unreachable. */
    public Optional<ConsoleConfig> fetchConfig(String service) {
        try {
            return fetchConfigOrThrow(service);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (Exception e) {
            LOG.debug("Console unreachable at {}: {}", baseUrl, e.toString());
            return Optional.empty();
        }
    }

    /** Empty when the console has no entry for the service (HTTP 404); throws when it cannot be read. */
    Optional<ConsoleConfig> fetchConfigOrThrow(String service) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/services/" + service + "/config"))
                .timeout(timeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() == 404) {
            return Optional.empty();
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("HTTP " + response.statusCode());
        }
        return Optional.of(mapper.readValue(response.body(), ConsoleConfig.class));
    }

    /** Console settings flattened into Spring properties, for use before the context starts. */
    Map<String, Object> fetchStartupOverrides(String service, org.apache.commons.logging.Log log) {
        Map<String, Object> overrides = new LinkedHashMap<>();
        Optional<ConsoleConfig> config = Optional.empty();
        // The container network is not always ready in the first second of start-up, so retry briefly.
        for (int attempt = 1; ; attempt++) {
            try {
                config = fetchConfigOrThrow(service);
                break;
            } catch (Exception e) {
                if (e instanceof InterruptedException || attempt == STARTUP_ATTEMPTS) {
                    if (e instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn(("Console at %s unreachable after %d attempts (%s); %s starts on local configuration "
                            + "and picks up log levels once it connects").formatted(baseUrl, attempt, e, service));
                    return overrides;
                }
                try {
                    Thread.sleep(1000L * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return overrides;
                }
            }
        }
        config.ifPresentOrElse(c -> {
            c.properties().forEach(overrides::put);
            c.logLevels().forEach((logger, level) -> overrides.put("logging.level." + logger, level));
            log.info("Applied %d console override(s) for %s (config version %d)".formatted(overrides.size(), service, c.version()));
        }, () -> log.info("Console has no entry for %s; starting on local configuration".formatted(service)));
        return overrides;
    }

    public void sendHeartbeat(String service, Heartbeat heartbeat) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/services/" + service + "/heartbeat"))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(heartbeat)))
                    .build();
            client.send(request, HttpResponse.BodyHandlers.discarding());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.debug("Heartbeat to console failed: {}", e.toString());
        }
    }
}
