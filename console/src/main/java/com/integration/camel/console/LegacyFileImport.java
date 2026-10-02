package com.integration.camel.console;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Map;
import java.util.TreeMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import com.integration.camel.console.Model.AuditEntry;
import com.integration.camel.console.Model.ServiceConfig;
import com.integration.camel.console.Model.ServiceEntry;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Imports the pre-PostgreSQL file store (store.json + audit.jsonl) once. Runs right after the Flyway
 * migrations, before the web server accepts heartbeats, under a PostgreSQL advisory lock; a marker row
 * (legacy_import) makes it once per database even with several console replicas.
 */
@Configuration(proxyBeanMethods = false)
public class LegacyFileImport {

    private static final Logger LOG = LoggerFactory.getLogger(LegacyFileImport.class);
    private static final long LOCK_KEY = 0x636f6e736f6c65L; // "console"

    private final JsonMapper mapper;
    private final ConsoleProperties properties;

    public LegacyFileImport(JsonMapper mapper, ConsoleProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Bean
    FlywayMigrationStrategy migrateThenImportLegacyFiles() {
        return flyway -> {
            flyway.migrate();
            importOnce(flyway.getConfiguration().getDataSource(), flyway.getConfiguration().getDefaultSchema(),
                    Path.of(properties.dataDir()));
        };
    }

    void importOnce(DataSource dataSource, String schema, Path dataDir) {
        Path store = dataDir.resolve("store.json");
        if (!Files.exists(store)) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            if (schema != null) {
                connection.setSchema(schema);
            }
            try {
                JdbcClient jdbc = JdbcClient.create(new SingleConnectionDataSource(connection, true));
                jdbc.sql("SELECT pg_advisory_xact_lock(?)").param(LOCK_KEY).query().singleRow();
                if (jdbc.sql("SELECT count(*) FROM legacy_import").query(Long.class).single() == 0) {
                    importFiles(jdbc, store, dataDir.resolve("audit.jsonl"));
                }
                connection.commit();
            } catch (RuntimeException e) {
                connection.rollback();
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Legacy file store import failed", e);
        }
    }

    private void importFiles(JdbcClient jdbc, Path store, Path audit) {
        // The file format predates `localOnly`; missing fields read as false/null.
        Map<String, ServiceEntry> legacy = mapper.readerFor(new TypeReference<Map<String, ServiceEntry>>() { })
                .without(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
                .readValue(store.toFile());
        for (ServiceEntry e : legacy.values()) {
            ServiceConfig c = e.config();
            jdbc.sql("INSERT INTO service (name, domain, description, port) VALUES (?, ?, ?, ?) ON CONFLICT (name) DO NOTHING")
                    .params(e.name(), e.domain(), e.description(), e.port()).update();
            // A service seen before the import (version 0, never edited) takes the imported settings.
            jdbc.sql("""
                    INSERT INTO service_config (service, version, log_levels, properties, updated_at, updated_by)
                    VALUES (?, ?, ?::jsonb, ?::jsonb, ?, ?)
                    ON CONFLICT (service) DO UPDATE SET version = EXCLUDED.version, log_levels = EXCLUDED.log_levels,
                        properties = EXCLUDED.properties, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by
                    WHERE service_config.version = 0""")
                    .params(e.name(), c.version(), json(c.logLevels()), json(c.properties()),
                            c.updatedAt() == null ? null : Timestamp.from(c.updatedAt()), c.updatedBy())
                    .update();
        }
        int imported = 0;
        if (Files.exists(audit)) {
            try {
                for (String line : Files.readAllLines(audit, StandardCharsets.UTF_8)) {
                    if (line.isBlank()) {
                        continue;
                    }
                    AuditEntry a = mapper.readValue(line, AuditEntry.class);
                    jdbc.sql("""
                            INSERT INTO config_audit (at, service, changed_by, comment, from_version, to_version, log_levels, properties)
                            VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb) ON CONFLICT DO NOTHING""")
                            .params(Timestamp.from(a.at()), a.service(), a.changedBy(), a.comment(), a.fromVersion(),
                                    a.toVersion(), json(a.logLevels()), json(a.properties()))
                            .update();
                    imported++;
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        jdbc.sql("INSERT INTO legacy_import (id, source) VALUES (1, ?)").param(store.toString()).update();
        LOG.info("Imported {} service(s) and {} audit entries from the legacy file store {}", legacy.size(), imported, store);
    }

    private String json(Map<String, String> map) {
        return mapper.writeValueAsString(map == null ? Map.of() : new TreeMap<>(map));
    }
}
