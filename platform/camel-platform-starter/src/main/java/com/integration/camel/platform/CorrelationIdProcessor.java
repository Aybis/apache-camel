package com.integration.camel.platform;

import java.util.UUID;

import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.slf4j.MDC;

/**
 * Ensures every exchange carries a correlation ID: taken from the configured inbound header,
 * else from the JMS correlation ID, else from the MDC (a nested call made from inside another
 * exchange on the same thread inherits the caller's ID), else generated. It is set back on the header (so it travels
 * to downstream systems), as the exchange property {@value #PROPERTY}, and in the MDC so it
 * appears on every log line and can be searched in Loki.
 */
public class CorrelationIdProcessor implements Processor {

    public static final String PROPERTY = "correlationId";
    public static final String MDC_KEY = "correlationId";

    private final String header;

    public CorrelationIdProcessor(String header) {
        this.header = header;
    }

    @Override
    public void process(Exchange exchange) {
        String id = exchange.getProperty(PROPERTY, String.class);
        if (id == null) {
            id = exchange.getIn().getHeader(header, String.class);
        }
        if (id == null || id.isBlank()) {
            id = exchange.getIn().getHeader("JMSCorrelationID", String.class);
        }
        if (id == null || id.isBlank()) {
            id = MDC.get(MDC_KEY);
        }
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        exchange.getIn().setHeader(header, id);
        exchange.setProperty(PROPERTY, id);
        MDC.put(MDC_KEY, id);
    }

    public String getHeader() {
        return header;
    }
}
