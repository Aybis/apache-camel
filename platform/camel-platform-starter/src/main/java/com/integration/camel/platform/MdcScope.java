package com.integration.camel.platform;

import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * Runs a nested Camel call (a {@code ProducerTemplate} call made inside a processor) and restores the
 * caller's MDC afterwards. With {@code camel.main.use-mdc-logging} the nested exchange's unit of work
 * clears the MDC when it completes, so without this every log line after the call loses
 * {@code correlationId} and the Camel route fields.
 *
 * <pre>{@code
 * Exchange reply = MdcScope.preserving(() -> producer.request("direct:bank-call", ex -> ...));
 * }</pre>
 *
 * The nested exchange itself keeps the caller's correlation ID: {@link CorrelationIdProcessor} takes it
 * from the MDC when the nested exchange carries no correlation header.
 */
public final class MdcScope {

    private MdcScope() {
    }

    public static <T> T preserving(Supplier<T> call) {
        Map<String, String> saved = MDC.getCopyOfContextMap();
        try {
            return call.get();
        } finally {
            if (saved != null) {
                MDC.setContextMap(saved);
            } else {
                MDC.clear();
            }
        }
    }

    public static void preserving(Runnable call) {
        preserving(() -> {
            call.run();
            return null;
        });
    }
}
