package com.integration.camel.paymentgateway.core;

import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.platform.CorrelationIdProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * Route configuration for the synchronous payment APIs. It replaces the platform default on these routes
 * on purpose: the default dead letter channel redelivers a failed exchange, and redelivering a
 * money-moving request is how a transfer gets sent twice. Here a failure becomes an HTTP error response
 * and is never retried; resolution of uncertain outcomes is the reconciliation route's job.
 */
@Component
public class PaymentApiConfiguration extends RouteConfigurationBuilder {

    public static final String ID = "payment-api";

    /** Event delivery: retried (no money moves), and a failure is logged without the body. */
    public static final String EVENTS_ID = "payment-events";

    private final CorrelationIdProcessor correlation;
    private final JsonMapper json;

    public PaymentApiConfiguration(CorrelationIdProcessor correlation, JsonMapper json) {
        this.correlation = correlation;
        this.json = json;
    }

    @Override
    public void configuration() {
        routeConfiguration(ID)
                .interceptFrom().process(correlation).end()
                .onException(PaymentException.class).handled(true)
                    .process(ex -> {
                        PaymentException e = ex.getProperty(Exchange.EXCEPTION_CAUGHT, PaymentException.class);
                        error(ex, e.httpStatus(), e.code(), e.getMessage());
                    })
                .end()
                .onException(tools.jackson.core.JacksonException.class).handled(true)
                    .process(ex -> error(ex, 400, "INVALID_JSON",
                            ex.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class).getMessage()))
                .end()
                .onException(Exception.class).handled(true)
                    .log(org.apache.camel.LoggingLevel.ERROR, "Unhandled error: ${exception.stacktrace}")
                    .process(ex -> error(ex, 500, "INTERNAL_ERROR", "Internal error; see logs for the correlation ID"))
                .end();

        // Events move no money, so they are retried; when that fails, the log line names the event, payment id
        // and status (never the body: it carries account numbers and names). The event is then lost; a
        // transactional outbox is the planned fix.
        routeConfiguration(EVENTS_ID)
                .onException(Exception.class).handled(true)
                    .maximumRedeliveries(3).redeliveryDelay(1000).backOffMultiplier(2).useExponentialBackOff()
                    .log(org.apache.camel.LoggingLevel.ERROR, "Payment event ${header.paymentEvent} for "
                            + "${header.paymentId} (${header.paymentStatus}) could not be delivered: ${exception.message}")
                .end();
    }

    private void error(Exchange ex, int status, String code, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", code);
        body.put("message", message);
        body.put("correlationId", ex.getProperty(CorrelationIdProcessor.PROPERTY));
        PaymentRoutes.respond(ex, status, json.writeValueAsString(body));
    }
}
