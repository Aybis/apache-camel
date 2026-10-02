package __PACKAGE__;

import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.platform.PlatformLog;

/**
 * Routes for __SERVICE__.
 *
 * The platform already applies, to every route here: a correlation ID, a dead letter
 * channel with exponential back-off, MDC logging, metrics and JSON logs shipped to Loki.
 * For a transactional IBM MQ consumer add
 * {@code .routeConfigurationId(PlatformRouteConfiguration.TRANSACTED)} instead.
 *
 * Replace the placeholder route below with the migrated ACE message flow.
 */
@Component
public class __CLASS__Routes extends RouteBuilder {

    @Override
    public void configure() {
        from("platform-http:/api/__SERVICE__/ping?httpMethodRestrict=GET")
                .routeId("__SERVICE__-ping")
                .process(exchange -> PlatformLog.event(log, exchange, "__SERVICE__.ping"))
                .setBody(constant("{\"service\":\"__SERVICE__\",\"status\":\"ok\"}"))
                .setHeader("Content-Type", constant("application/json"));
    }
}
