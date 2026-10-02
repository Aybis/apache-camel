package com.integration.camel.console;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import com.integration.camel.console.Model.AuditEntry;
import com.integration.camel.console.Model.ConfigChange;
import com.integration.camel.console.Model.ServiceConfig;
import com.integration.camel.console.Model.ServiceEntry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * File-backed store of service settings with an append-only audit trail.
 * Writes are atomic (temp file then move). Swap for a database before running more than
 * one console replica: this store assumes a single writer.
 */
@Component
public class ConfigStore {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);
    private static final Pattern LOGGER_NAME = Pattern.compile("ROOT|[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern PROPERTY_KEY = Pattern.compile("[a-z0-9][a-z0-9.\\-\\[\\]_]*");
    /**
     * Connection settings and credentials stay in Git and the secret store, not in the console
     * (standards doc): a typo here could repoint production traffic with no code review.
     */
    private static final Pattern CONNECTION_KEY = Pattern.compile(
            ".*(url|uri|host|port|endpoint|address|password|passwd|secret|token|credential|username|"
                    + "key-store|trust-store|keystore|truststore|queue-manager|queuemanager|channel|conn-name|ccdt|ssl).*");
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private final JsonMapper mapper;
    private final Path storeFile;
    private final Path auditFile;
    private final Map<String, ServiceEntry> services = new TreeMap<>();

    public ConfigStore(ConsoleProperties properties, JsonMapper mapper) throws IOException {
        this.mapper = mapper;
        Path dir = Path.of(properties.dataDir());
        Files.createDirectories(dir);
        this.storeFile = dir.resolve("store.json");
        this.auditFile = dir.resolve("audit.jsonl");
        load();
        seedFromRegistry(Path.of(properties.registry()));
    }

    private void load() throws IOException {
        if (Files.exists(storeFile)) {
            Map<String, ServiceEntry> loaded = mapper.readValue(storeFile.toFile(), new TypeReference<>() { });
            services.putAll(loaded);
            LOG.info("Loaded {} service(s) from {}", services.size(), storeFile);
        }
    }

    @SuppressWarnings("unchecked")
    private void seedFromRegistry(Path registry) {
        if (!Files.exists(registry)) {
            LOG.warn("Service registry {} not found; services will appear when they first send a heartbeat", registry);
            return;
        }
        try {
            Map<String, Object> root = new Yaml().load(Files.readString(registry));
            List<Map<String, Object>> entries = root == null || root.get("services") == null
                    ? List.of() : (List<Map<String, Object>>) root.get("services");
            for (Map<String, Object> e : entries) {
                String name = String.valueOf(e.get("name"));
                ServiceEntry existing = services.get(name);
                ServiceConfig config = existing != null ? existing.config() : ServiceConfig.initial(name);
                services.put(name, new ServiceEntry(name, String.valueOf(e.getOrDefault("domain", "unassigned")),
                        String.valueOf(e.getOrDefault("description", "")),
                        e.get("port") instanceof Number n ? n.intValue() : null, config));
            }
            persist();
            LOG.info("Registry {} lists {} service(s)", registry, entries.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized List<ServiceEntry> all() {
        return List.copyOf(services.values());
    }

    public synchronized Optional<ServiceEntry> find(String name) {
        return Optional.ofNullable(services.get(name));
    }

    /** Adds a service first seen through its heartbeat. */
    public synchronized void registerIfAbsent(String name, String domain) {
        if (!services.containsKey(name)) {
            services.put(name, new ServiceEntry(name, domain == null ? "unassigned" : domain, "", null,
                    ServiceConfig.initial(name)));
            persist();
            LOG.info("Registered {} from its first heartbeat", name);
        }
    }

    public synchronized ServiceConfig update(String name, ConfigChange change) {
        ServiceEntry entry = services.get(name);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown service " + name);
        }
        Map<String, String> levels = normaliseLevels(change.logLevels());
        Map<String, String> props = validateProperties(change.properties());
        String by = change.changedBy() == null || change.changedBy().isBlank() ? "unknown" : change.changedBy().trim();

        ServiceConfig previous = entry.config();
        ServiceConfig next = new ServiceConfig(name, previous.version() + 1, levels, props, Instant.now(), by);
        services.put(name, new ServiceEntry(entry.name(), entry.domain(), entry.description(), entry.port(), next));
        persist();
        appendAudit(new AuditEntry(next.updatedAt(), name, by, change.comment(), previous.version(), next.version(),
                levels, props));
        LOG.atInfo().addKeyValue("event", "config.changed").addKeyValue("service", name)
                .addKeyValue("version", next.version()).addKeyValue("changedBy", by)
                .log("Configuration of {} changed to version {}", name, next.version());
        return next;
    }

    public List<AuditEntry> audit(String service, int limit) {
        if (!Files.exists(auditFile)) {
            return List.of();
        }
        try {
            List<AuditEntry> result = new ArrayList<>();
            for (String line : Files.readAllLines(auditFile, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                AuditEntry e = mapper.readValue(line, AuditEntry.class);
                if (service == null || service.equals(e.service())) {
                    result.add(e);
                }
            }
            Collections.reverse(result);
            return result.subList(0, Math.min(limit, result.size()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Map<String, String> normaliseLevels(Map<String, String> input) {
        Map<String, String> out = new TreeMap<>();
        if (input == null) {
            return out;
        }
        input.forEach((logger, level) -> {
            String name = logger == null ? "" : logger.trim();
            String lvl = level == null ? "" : level.trim().toUpperCase();
            if (!LOGGER_NAME.matcher(name).matches()) {
                throw new IllegalArgumentException("Invalid logger name: " + logger);
            }
            if (!LEVELS.contains(lvl)) {
                throw new IllegalArgumentException("Invalid level for " + name + ": " + level + " (use " + LEVELS + ")");
            }
            out.put(name.equalsIgnoreCase("root") ? "ROOT" : name, lvl);
        });
        return out;
    }

    private static Map<String, String> validateProperties(Map<String, String> input) {
        Map<String, String> out = new TreeMap<>();
        if (input == null) {
            return out;
        }
        input.forEach((key, value) -> {
            String k = key == null ? "" : key.trim();
            if (!PROPERTY_KEY.matcher(k).matches()) {
                throw new IllegalArgumentException("Invalid property key: " + key + " (use kebab-case, e.g. platform.error-handling.maximum-redeliveries)");
            }
            if (k.startsWith("logging.level.")) {
                throw new IllegalArgumentException("Set log levels in the log level section, not as property " + k);
            }
            if (CONNECTION_KEY.matcher(k).matches()) {
                throw new IllegalArgumentException(k + " looks like a connection setting or credential; "
                        + "those are managed in Git and the secret store, not in the console");
            }
            if (k.equals("spring.application.name") || k.startsWith("platform.console.")) {
                throw new IllegalArgumentException(k + " cannot be changed from the console");
            }
            out.put(k, value == null ? "" : value);
        });
        return out;
    }

    private void persist() {
        try {
            Path tmp = storeFile.resolveSibling("store.json.tmp");
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), services);
            Files.move(tmp, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + storeFile, e);
        }
    }

    private void appendAudit(AuditEntry entry) {
        try {
            Files.writeString(auditFile, mapper.writeValueAsString(entry) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + auditFile, e);
        }
    }
}
