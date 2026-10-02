package com.integration.camel.paymentgateway.core;

import java.util.Map;
import java.util.function.Supplier;

import org.slf4j.MDC;

/**
 * Runs a nested Camel call (ProducerTemplate) and restores the caller's MDC afterwards. With
 * {@code camel.main.use-mdc-logging} the nested exchange's unit of work clears the MDC when it completes,
 * which would otherwise drop the correlation ID from every log line after the call.
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
