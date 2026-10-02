package com.integration.camel.banksimulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The simulator's own checks. Full flows against the gateway run in payment-gateway's PaymentGatewayFlowTest,
 * which loads these routes.
 */
@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"platform.console.enabled=false", "simulator.partner.client-key=gateway-client"})
class BankSimulatorRoutesTest {

    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @Autowired
    CamelContext camelContext;

    @Value("${local.server.port}")
    int port;

    @Test
    void routesStart() {
        assertThat(camelContext.getRoute("bank-simulator-token")).isNotNull();
        assertThat(camelContext.getRoute("bank-simulator-intrabank")).isNotNull();
        assertThat(camelContext.getRoute("bank-simulator-pay-va")).isNotNull();
    }

    @Test
    void unsignedRequestsAreRejectedLikeABankWould() throws Exception {
        HttpResponse<String> token = post("/bni/v1.0/access-token/b2b", "{\"grantType\":\"client_credentials\"}");
        assertThat(token.statusCode()).isEqualTo(401);
        assertThat(token.body()).contains("4017300");

        HttpResponse<String> transfer = post("/bni/v1.0/transfer-intrabank", "{}");
        assertThat(transfer.statusCode()).isEqualTo(401);
        assertThat(transfer.body()).contains("4011701");
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Content-Type", "application/json").header("X-CLIENT-KEY", "gateway-client")
                .header("X-TIMESTAMP", "2026-10-02T10:00:00+07:00").header("X-SIGNATURE", "AAAA")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
