package com.integration.camel.platform;

import java.util.HashSet;
import java.util.Set;

import org.apache.camel.Exchange;
import org.slf4j.Logger;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Structured business-event logging in Elastic Common Schema form: the event name goes to
 * {@code event.action} and each key/value to {@code labels.<key>}, so they can be filtered in Loki
 * ({@code | json | event_action="order.received"}) without parsing message text.
 *
 * <pre>{@code
 * PlatformLog.event(log, exchange, "order.received", "orderId", id, "lines", n);
 * }</pre>
 *
 * The correlation ID and route ID are not repeated here: they are already on every line via the MDC.
 * Keys are namespaced because a key that collides with an MDC or ECS field makes the JSON
 * encoder drop the whole line.
 */
public final class PlatformLog {

    private PlatformLog() {
    }

    public static void event(Logger log, Exchange exchange, String event, Object... keyValues) {
        event(log, Level.INFO, exchange, event, keyValues);
    }

    public static void event(Logger log, Level level, Exchange exchange, String event, Object... keyValues) {
        if (keyValues.length % 2 != 0) {
            throw new IllegalArgumentException("keyValues must be key/value pairs");
        }
        if (!log.isEnabledForLevel(level)) {
            return;
        }
        LoggingEventBuilder builder = log.atLevel(level).addKeyValue("event.action", event);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = String.valueOf(keyValues[i]).replace('.', '_');
            if (seen.add(key)) {
                builder = builder.addKeyValue("labels." + key, keyValues[i + 1]);
            }
        }
        builder.log(event);
    }
}
