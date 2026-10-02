package com.integration.camel.console;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.integration.camel.console.Model.Heartbeat;
import com.integration.camel.console.Model.InstanceView;
import com.integration.camel.console.Model.Status;

import tools.jackson.databind.json.JsonMapper;

/**
 * Latest heartbeat per service instance, kept in PostgreSQL so every console replica sees every
 * instance whichever replica received the heartbeat.
 */
@Component
public class HeartbeatRegistry {

    private final JdbcClient jdbc;
    private final JsonMapper mapper;
    private final ConsoleProperties properties;

    public HeartbeatRegistry(JdbcClient jdbc, JsonMapper mapper, ConsoleProperties properties) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.properties = properties;
    }

    /** The service must already be registered (the caller registers it first). */
    public void record(String service, Heartbeat heartbeat) {
        jdbc.sql("""
                INSERT INTO heartbeat (service, instance, payload, received_at) VALUES (?, ?, ?::jsonb, ?)
                ON CONFLICT (service, instance) DO UPDATE SET payload = EXCLUDED.payload, received_at = EXCLUDED.received_at""")
                .params(service, heartbeat.instance(), mapper.writeValueAsString(heartbeat), Timestamp.from(Instant.now()))
                .update();
    }

    public List<InstanceView> instances(String service) {
        Instant now = Instant.now();
        Instant staleBefore = now.minus(properties.staleAfter());
        Instant forgetBefore = now.minus(properties.staleAfter().multipliedBy(10));
        jdbc.sql("DELETE FROM heartbeat WHERE service = ? AND received_at < ?")
                .params(service, Timestamp.from(forgetBefore)).update();
        return jdbc.sql("SELECT payload, received_at FROM heartbeat WHERE service = ? ORDER BY instance")
                .param(service)
                .query((rs, i) -> {
                    Instant at = rs.getTimestamp("received_at").toInstant();
                    return new InstanceView(mapper.readValue(rs.getString("payload"), Heartbeat.class), at,
                            at.isBefore(staleBefore));
                })
                .list();
    }

    public static Status status(List<InstanceView> instances) {
        if (instances.isEmpty()) {
            return Status.UNKNOWN;
        }
        if (instances.stream().anyMatch(i -> !i.stale() && "UP".equals(i.heartbeat().status()))) {
            return Status.UP;
        }
        if (instances.stream().allMatch(i -> "STOPPING".equals(i.heartbeat().status()))) {
            return Status.DOWN;
        }
        return Status.STALE;
    }
}
