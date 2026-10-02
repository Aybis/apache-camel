package __PACKAGE__;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Once-only processing keyed by message id (table processed_message, V1 migration). */
@Component
public class ProcessedMessages {

    private final JdbcClient jdbc;
    private final Duration retention;

    public ProcessedMessages(JdbcClient jdbc, @Value("${processed-message.retention:30d}") Duration retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    /** True the first time this id is seen; false for a duplicate. Call before acting on the message. */
    public boolean claim(String messageId) {
        return jdbc.sql("INSERT INTO processed_message (message_id) VALUES (?) ON CONFLICT DO NOTHING")
                .param(messageId).update() == 1;
    }

    /** Deletes ids older than the retention; returns how many. Duplicates older than that are no longer caught. */
    public int purge() {
        return jdbc.sql("DELETE FROM processed_message WHERE processed_at < ?")
                .param(Timestamp.from(Instant.now().minus(retention))).update();
    }
}
