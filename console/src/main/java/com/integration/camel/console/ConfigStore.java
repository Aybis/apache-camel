package com.integration.camel.console;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

import com.integration.camel.console.Model.AuditEntry;
import com.integration.camel.console.Model.ConfigChange;
import com.integration.camel.console.Model.ServiceConfig;
import com.integration.camel.console.Model.ServiceEntry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Service settings with an append-only audit trail, stored in PostgreSQL (schema in
 * db/migration). Safe with several console replicas: a change locks the service's settings row,
 * so concurrent saves get consecutive versions instead of overwriting each other.
 */
@Component
@Order(0)
public class ConfigStore implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigStore.class);
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };
    private static final Pattern LOGGER_NAME = Pattern.compile("ROOT|[A-Za-z_$][\\w$]*(\\.[A-Za-z_$][\\w$]*)*");
    private static final Pattern PROPERTY_KEY = Pattern.compile("[a-z0-9][a-z0-9.\\-\\[\\]_]*");
    /**
     * Connection settings and credentials stay in Git and the secret store, not in the console
     * (standards doc): a typo here could repoint production traffic with no code review.
     */
    private static final Pattern CONNECTION_KEY = Pattern.compile(
            "(.*[.\\-])?(url|uri|host|hosts|port|endpoint|address|password|passwd|secret|token|credential|username|"
                    + "key-store|trust-store|keystore|truststore|queue-manager|queuemanager|channel|conn-name|ccdt|ssl)([.\\-].*)?");
    private static final List<String> LEVELS = List.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "OFF");

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final ConsoleProperties properties;

    public ConfigStore(JdbcClient jdbc, JsonMapper mapper, ConsoleProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seedFromRegistry(Path.of(properties.registry()));
    }

    // ---------- reads ----------

    public List<ServiceEntry> all() {
        return jdbc.sql(SELECT_ENTRY + " ORDER BY s.name").query(this::entry).list();
    }

    public Optional<ServiceEntry> find(String name) {
        return jdbc.sql(SELECT_ENTRY + " WHERE s.name = ?").param(name).query(this::entry).optional();
    }

    public List<AuditEntry> audit(String service, int limit) {
        String sql = "SELECT * FROM config_audit" + (service == null ? "" : " WHERE service = :service")
                + " ORDER BY at DESC, id DESC LIMIT :limit";
        JdbcClient.StatementSpec spec = jdbc.sql(sql).param("limit", limit);
        if (service != null) {
            spec = spec.param("service", service);
        }
        return spec.query((rs, i) -> new AuditEntry(instant(rs, "at"), rs.getString("service"),
                rs.getString("changed_by"), rs.getString("comment"), rs.getLong("from_version"),
                rs.getLong("to_version"), map(rs.getString("log_levels")), map(rs.getString("properties")))).list();
    }

    // ---------- writes ----------

    /** Adds a service first seen through its heartbeat. */
    @Transactional
    public void registerIfAbsent(String name, String domain) {
        int added = jdbc.sql("INSERT INTO service (name, domain) VALUES (?, ?) ON CONFLICT (name) DO NOTHING")
                .params(name, domain == null ? "unassigned" : domain).update();
        if (added > 0) {
            jdbc.sql("INSERT INTO service_config (service) VALUES (?) ON CONFLICT DO NOTHING").param(name).update();
            LOG.info("Registered {} from its first heartbeat", name);
        }
    }

    @Transactional
    public ServiceConfig update(String name, ConfigChange change) {
        Map<String, String> levels = normaliseLevels(change.logLevels());
        Map<String, String> props = validateProperties(change.properties());
        String by = change.changedBy() == null || change.changedBy().isBlank() ? "unknown" : change.changedBy().trim();

        Long previous = jdbc.sql("SELECT version FROM service_config WHERE service = ? FOR UPDATE")
                .param(name).query(Long.class).optional()
                .orElseThrow(() -> new IllegalArgumentException("Unknown service " + name));
        long next = previous + 1;
        Instant now = Instant.now();
        jdbc.sql("""
                UPDATE service_config SET version = ?, log_levels = ?::jsonb, properties = ?::jsonb,
                       updated_at = ?, updated_by = ? WHERE service = ?""")
                .params(next, json(levels), json(props), Timestamp.from(now), by, name).update();
        jdbc.sql("""
                INSERT INTO config_audit (at, service, changed_by, comment, from_version, to_version, log_levels, properties)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""")
                .params(Timestamp.from(now), name, by, change.comment(), previous, next, json(levels), json(props))
                .update();
        LOG.atInfo().addKeyValue("event.action", "config.changed").addKeyValue("labels.service", name)
                .addKeyValue("labels.version", next).addKeyValue("labels.changedBy", by)
                .log("Configuration of {} changed to version {}", name, next);
        return new ServiceConfig(name, next, levels, props, now, by);
    }

    // ---------- start-up ----------

    @SuppressWarnings("unchecked")
    void seedFromRegistry(Path registry) {
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
                jdbc.sql("""
                        INSERT INTO service (name, domain, description, port, local_only) VALUES (?, ?, ?, ?, ?)
                        ON CONFLICT (name) DO UPDATE SET domain = EXCLUDED.domain, description = EXCLUDED.description,
                            port = EXCLUDED.port, local_only = EXCLUDED.local_only""")
                        .params(name, String.valueOf(e.getOrDefault("domain", "unassigned")),
                                String.valueOf(e.getOrDefault("description", "")),
                                e.get("port") instanceof Number n ? n.intValue() : null,
                                Boolean.TRUE.equals(e.get("local-only")))
                        .update();
                jdbc.sql("INSERT INTO service_config (service) VALUES (?) ON CONFLICT DO NOTHING").param(name).update();
            }
            LOG.info("Registry {} lists {} service(s)", registry, entries.size());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---------- mapping ----------

    private static final String SELECT_ENTRY = """
            SELECT s.name, s.domain, s.description, s.port, s.local_only,
                   c.version, c.log_levels, c.properties, c.updated_at, c.updated_by
            FROM service s JOIN service_config c ON c.service = s.name""";

    private ServiceEntry entry(ResultSet rs, int row) throws SQLException {
        String name = rs.getString("name");
        int port = rs.getInt("port");
        return new ServiceEntry(name, rs.getString("domain"), rs.getString("description"),
                rs.wasNull() ? null : port, rs.getBoolean("local_only"),
                new ServiceConfig(name, rs.getLong("version"), map(rs.getString("log_levels")),
                        map(rs.getString("properties")), instant(rs, "updated_at"), rs.getString("updated_by")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }

    private Map<String, String> map(String json) {
        return json == null ? Map.of() : new TreeMap<>(mapper.readValue(json, STRING_MAP));
    }

    private String json(Map<String, String> map) {
        return mapper.writeValueAsString(map == null ? Map.of() : new TreeMap<>(map));
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
}
