package com.integration.camel.banksimulator;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.platform.CorrelationIdProcessor;

import tools.jackson.databind.json.JsonMapper;

/**
 * Replaces the platform default on the simulator's routes: a bank answers each request exactly once, so no
 * redelivery (which would apply a transfer twice); an unexpected error becomes a SNAP 500 response.
 */
@Component
public class SimulatorRouteConfiguration extends RouteConfigurationBuilder {

    public static final String ID = "bank-simulator";

    private final CorrelationIdProcessor correlation;
    private final JsonMapper json;

    public SimulatorRouteConfiguration(CorrelationIdProcessor correlation, JsonMapper json) {
        this.correlation = correlation;
        this.json = json;
    }

    @Override
    public void configuration() {
        routeConfiguration(ID)
                .interceptFrom().process(correlation).end()
                .onException(Exception.class).handled(true)
                    .log(LoggingLevel.ERROR, "Simulator error: ${exception}")
                    .process(ex -> BankSimulatorRoutes.reply(json, ex, 500, "5000001", "General Error"))
                .end();
    }
}
