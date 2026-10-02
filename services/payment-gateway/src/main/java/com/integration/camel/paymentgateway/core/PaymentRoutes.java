package com.integration.camel.paymentgateway.core;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.camel.Exchange;
import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.TransferRequest;
import com.integration.camel.paymentgateway.api.VirtualAccount;
import com.integration.camel.paymentgateway.api.VirtualAccountRequest;
import com.integration.camel.paymentgateway.spi.InboundRequest;
import com.integration.camel.paymentgateway.spi.InboundResponse;
import com.integration.camel.platform.CorrelationIdProcessor;
import com.integration.camel.platform.PlatformLog;

import tools.jackson.databind.json.JsonMapper;

/**
 * HTTP surface of the gateway, and its background flows.
 * <pre>
 * POST /api/payments/v1/transfers               send a transfer (idempotent on clientReferenceId)
 * GET  /api/payments/v1/transfers/{id}          read it; ?refresh=true asks the bank if not final
 * POST /api/payments/v1/virtual-accounts        open a virtual account
 * GET  /api/payments/v1/virtual-accounts/{id}   read it (status SUCCESS once paid)
 * GET  /api/payments/v1/banks                   enabled banks and what each supports
 * POST /inbound/{bank}/...                      calls from banks (access token, VA payment notification)
 * </pre>
 */
@Component
public class PaymentRoutes extends RouteBuilder {

    private static final String API = "/api/payments/v1";

    private final PaymentService service;
    private final BankAdapterRegistry adapters;
    private final PaymentProperties properties;
    private final JsonMapper json;

    public PaymentRoutes(PaymentService service, BankAdapterRegistry adapters, PaymentProperties properties,
                         JsonMapper json) {
        this.service = service;
        this.adapters = adapters;
        this.properties = properties;
        this.json = json;
    }

    @Override
    public void configure() {
        from("platform-http:" + API + "/transfers?httpMethodRestrict=POST")
                .routeId("payment-gateway-transfer-create")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(this::authorise)
                .process(ex -> {
                    TransferRequest req = json.readValue(ex.getIn().getBody(String.class), TransferRequest.class);
                    PlatformLog.event(log, ex, "payment.transfer.received", "bank", req.bank(),
                            "reference", req.clientReferenceId());
                    var result = service.submitTransfer(req);
                    Transfer t = result.value();
                    PlatformLog.event(log, ex, "payment.transfer.result", "reference", t.id(),
                            "status", t.status(), "bankCode", t.bankResponseCode(), "replayed", !result.created());
                    respond(ex, result.created() ? 201 : 200, json.writeValueAsString(t));
                });

        from("platform-http:" + API + "/transfers/{id}?httpMethodRestrict=GET")
                .routeId("payment-gateway-transfer-get")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(this::authorise)
                .process(ex -> respond(ex, 200, json.writeValueAsString(service.getTransfer(
                        ex.getIn().getHeader("id", String.class),
                        "true".equals(ex.getIn().getHeader("refresh", String.class))))));

        from("platform-http:" + API + "/virtual-accounts?httpMethodRestrict=POST")
                .routeId("payment-gateway-va-create")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(this::authorise)
                .process(ex -> {
                    VirtualAccountRequest req = json.readValue(ex.getIn().getBody(String.class),
                            VirtualAccountRequest.class);
                    var result = service.createVirtualAccount(req);
                    VirtualAccount va = result.value();
                    PlatformLog.event(log, ex, "payment.va.result", "reference", va.id(), "status", va.status(),
                            "virtualAccountNo", va.virtualAccountNo(), "bankCode", va.bankResponseCode());
                    respond(ex, result.created() ? 201 : 200, json.writeValueAsString(va));
                });

        from("platform-http:" + API + "/virtual-accounts/{id}?httpMethodRestrict=GET")
                .routeId("payment-gateway-va-get")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(this::authorise)
                .process(ex -> respond(ex, 200, json.writeValueAsString(
                        service.getVirtualAccount(ex.getIn().getHeader("id", String.class)))));

        from("platform-http:" + API + "/banks?httpMethodRestrict=GET")
                .routeId("payment-gateway-banks")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(this::authorise)
                .process(ex -> respond(ex, 200, json.writeValueAsString(adapters.all().stream()
                        .map(a -> Map.of("code", a.code(), "bankIdCode", a.bankIdCode(),
                                "capabilities", a.capabilities()))
                        .toList())));

        // Calls initiated by banks. Authentication is the adapter's job (each bank signs differently).
        from("platform-http:/inbound?matchOnUriPrefix=true&httpMethodRestrict=POST")
                .routeId("payment-gateway-inbound")
                .routeConfigurationId(PaymentApiConfiguration.ID)
                .process(ex -> {
                    String path = ex.getIn().getHeader(Exchange.HTTP_URI, String.class);
                    if (path.contains("?")) {
                        path = path.substring(0, path.indexOf('?'));
                    }
                    String[] parts = path.split("/");
                    if (parts.length < 3) {
                        throw PaymentException.notFound("Missing bank in path");
                    }
                    String bank = parts[2];
                    Map<String, String> headers = ex.getIn().getHeaders().entrySet().stream()
                            .filter(e -> e.getValue() != null)
                            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().toString(),
                                    (a, b) -> a, HashMap::new));
                    InboundRequest req = new InboundRequest(ex.getIn().getHeader(Exchange.HTTP_METHOD, String.class),
                            path, headers, ex.getIn().getBody(String.class));
                    InboundResponse res = service.handleInbound(bank, req);
                    PlatformLog.event(log, ex, "payment.inbound", "bank", bank, "path", path,
                            "httpStatus", res.httpStatus());
                    respond(ex, res.httpStatus(), res.body());
                });

        // Resolves PENDING and UNKNOWN transfers by asking the bank. Never re-sends a transfer.
        from("timer:payment-reconcile?delay=" + properties.getReconciliation().getInterval().toMillis()
                + "&period=" + properties.getReconciliation().getInterval().toMillis())
                .routeId("payment-gateway-reconcile")
                .process(ex -> ex.getMessage().setBody(service.reconcile()))
                .filter(body().isGreaterThan(0))
                    .log(LoggingLevel.INFO, log, "Reconciliation checked ${body} open transfer(s)");

        // Final outcomes go to downstream systems through a configurable endpoint (log, JMS queue, Kafka...).
        from(PaymentService.EVENTS_ENDPOINT)
                .routeId("payment-gateway-events")
                .routeConfigurationId(PaymentApiConfiguration.EVENTS_ID)
                .process(ex -> {
                    PlatformLog.event(log, ex, "payment.event", "type", ex.getIn().getHeader("paymentEvent"),
                            "id", ex.getIn().getHeader("paymentId"),
                            "status", ex.getIn().getHeader("paymentStatus"));
                    ex.getIn().setBody(json.writeValueAsString(ex.getIn().getBody()));
                })
                .toD(properties.getEventsUri());
    }

    private void authorise(Exchange ex) {
        String key = properties.getApiKey();
        if (key != null && !key.isBlank() && !key.equals(ex.getIn().getHeader("X-Api-Key", String.class))) {
            throw new PaymentException(401, "UNAUTHORIZED", "Missing or wrong X-Api-Key");
        }
    }

    /** Writes a JSON response and drops every request header, so nothing the caller sent is echoed back. */
    static void respond(Exchange ex, int status, String body) {
        Object correlationId = ex.getProperty(CorrelationIdProcessor.PROPERTY);
        ex.getMessage().removeHeaders("*");
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, status);
        ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        if (correlationId != null) {
            ex.getMessage().setHeader("X-Correlation-Id", correlationId);
        }
        ex.getMessage().setBody(body);
    }
}
