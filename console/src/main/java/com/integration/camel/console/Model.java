package com.integration.camel.console;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** API and storage records. Kept together because each is small. */
public final class Model {

    private Model() {
    }

    /** A service as stored: registry fields plus its current configuration. */
    public record ServiceEntry(String name, String domain, String description, Integer port, boolean localOnly,
                               ServiceConfig config) {
    }

    /** What services pull. logLevels apply live; properties apply on the next restart. */
    public record ServiceConfig(String service, long version, Map<String, String> logLevels,
                                Map<String, String> properties, Instant updatedAt, String updatedBy) {

        public ServiceConfig {
            logLevels = logLevels == null ? Map.of() : Map.copyOf(logLevels);
            properties = properties == null ? Map.of() : Map.copyOf(properties);
        }

        static ServiceConfig initial(String service) {
            return new ServiceConfig(service, 0, Map.of(), Map.of(), null, null);
        }
    }

    public record ConfigChange(Map<String, String> logLevels, Map<String, String> properties,
                               String changedBy, String comment) {
    }

    public record Heartbeat(String instance, String environment, String domain, String version,
                            String camelVersion, String status, long appliedConfigVersion,
                            long startedAtEpochMs, List<RouteState> routes) {
    }

    public record RouteState(String id, String status, String from) {
    }

    public record InstanceView(Heartbeat heartbeat, Instant receivedAt, boolean stale) {
    }

    /** UP: a fresh heartbeat; STALE: heartbeats stopped; UNKNOWN: never seen; DOWN: reported stopping. */
    public enum Status { UP, STALE, DOWN, UNKNOWN }

    public record ServiceView(String name, String domain, String description, Integer port, boolean localOnly, Status status,
                              long configVersion, boolean configPending, List<InstanceView> instances) {
    }

    public record AuditEntry(Instant at, String service, String changedBy, String comment, long fromVersion,
                             long toVersion, Map<String, String> logLevels, Map<String, String> properties) {
    }

    public record LogLine(String timestamp, String level, String logger, String message, String correlationId,
                          String routeId, String instance, String raw) {
    }
}
