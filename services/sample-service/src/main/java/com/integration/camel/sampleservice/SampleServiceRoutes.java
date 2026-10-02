package com.integration.camel.sampleservice;

import java.util.concurrent.ThreadLocalRandom;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.platform.PlatformLog;

/**
 * Routes for sample-service.
 *
 * The platform already applies, to every route here: a correlation ID, a dead letter
 * channel with exponential back-off, MDC logging, metrics and JSON logs shipped to Loki.
 * For a transactional IBM MQ consumer add
 * {@code .routeConfigurationId(PlatformRouteConfiguration.TRANSACTED)} instead.
 *
 * This reference service also simulates traffic so the dashboards and log views have data:
 * a timer emits an "order" every few seconds and roughly one in ten fails, which exercises
 * redelivery and the dead letter channel.
 */
@Component
public class SampleServiceRoutes extends RouteBuilder {

    @Override
    public void configure() {
        from("platform-http:/api/sample-service/ping?httpMethodRestrict=GET")
                .routeId("sample-service-ping")
                .process(exchange -> PlatformLog.event(log, exchange, "sample-service.ping"))
                .setBody(constant("{\"service\":\"sample-service\",\"status\":\"ok\"}"))
                .setHeader("Content-Type", constant("application/json"));

        from("timer:simulated-orders?period={{sample.order-interval:5000}}")
                .routeId("sample-service-orders")
                .process(exchange -> exchange.getIn().setBody(new SimulatedOrder(
                        "ORD-" + ThreadLocalRandom.current().nextInt(100000), ThreadLocalRandom.current().nextInt(1, 20))))
                .process(exchange -> {
                    SimulatedOrder order = exchange.getIn().getBody(SimulatedOrder.class);
                    PlatformLog.event(log, exchange, "order.received", "orderId", order.id(), "lines", order.lines());
                })
                // Pass the class logger so console log levels for this package apply to the Log EIP too.
                .log(LoggingLevel.DEBUG, log, "Validating ${body}")
                .process(exchange -> {
                    if (ThreadLocalRandom.current().nextInt(10) == 0) {
                        throw new IllegalStateException("Downstream system rejected the order");
                    }
                })
                .process(exchange -> PlatformLog.event(log, exchange, "order.delivered",
                        "orderId", exchange.getIn().getBody(SimulatedOrder.class).id()));
    }

    record SimulatedOrder(String id, int lines) {
    }
}
