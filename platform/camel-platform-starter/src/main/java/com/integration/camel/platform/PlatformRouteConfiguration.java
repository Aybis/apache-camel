package com.integration.camel.platform;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.apache.camel.model.RouteConfigurationDefinition;

/**
 * Route-level policies applied to every route in every service, so service code only
 * describes the integration itself (standards doc STD-ERR, STD-OBS).
 * <ul>
 *   <li><b>default</b> (no id): applies to every route that names no configuration. Adds the
 *       correlation ID and a dead letter channel with exponential back-off.</li>
 *   <li><b>{@value #TRANSACTED}</b>: for routes that consume transactionally from IBM MQ. It adds
 *       only the correlation ID and leaves error handling to the transaction, so a failure rolls
 *       the message back to the queue and the queue manager's backout threshold moves poison
 *       messages to the backout queue. Opt in with
 *       {@code .routeConfigurationId(PlatformRouteConfiguration.TRANSACTED)}.</li>
 * </ul>
 */
public class PlatformRouteConfiguration extends RouteConfigurationBuilder {

    public static final String TRANSACTED = "platform-transacted";

    private final PlatformProperties properties;
    private final CorrelationIdProcessor correlation;

    public PlatformRouteConfiguration(PlatformProperties properties, CorrelationIdProcessor correlation) {
        this.properties = properties;
        this.correlation = correlation;
    }

    @Override
    public void configuration() {
        PlatformProperties.ErrorHandling eh = properties.getErrorHandling();

        RouteConfigurationDefinition defaults = routeConfiguration();
        defaults.errorHandler(deadLetterChannel(deadLetterUri(eh))
                .maximumRedeliveries(eh.getMaximumRedeliveries())
                .redeliveryDelay(eh.getRedeliveryDelay())
                .backOffMultiplier(eh.getBackoffMultiplier())
                .useExponentialBackOff()
                .retryAttemptedLogLevel(LoggingLevel.WARN)
                .logExhausted(true)
                .logExhaustedMessageHistory(false)
                .logExhaustedMessageBody(false)
                .useOriginalMessage());
        defaults.interceptFrom().process(correlation);

        routeConfiguration(TRANSACTED).interceptFrom().process(correlation);
    }

    /**
     * The configured dead letter URI, or by default a log line with exchange ID, route, body type and
     * exception but no headers and no body (bodies and headers carry account numbers, names, tokens).
     * {@code platform.error-handling.log-body=true} adds the body, masked by {@link PlatformMaskingFormatter}
     * and cut to 1000 characters.
     */
    static String deadLetterUri(PlatformProperties.ErrorHandling eh) {
        if (eh.getDeadLetterUri() != null && !eh.getDeadLetterUri().isBlank()) {
            return eh.getDeadLetterUri();
        }
        return "log:platform.dead-letter?level=ERROR&showExchangeId=true&showRouteId=true&showBodyType=true"
                + "&showHeaders=false&showException=true&showCaughtException=true&showStackTrace=true"
                + (eh.isLogBody() ? "&showBody=true&logMask=true&maxChars=1000" : "&showBody=false");
    }
}
