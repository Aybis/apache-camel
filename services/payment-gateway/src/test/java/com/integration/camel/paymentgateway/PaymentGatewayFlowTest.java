package com.integration.camel.paymentgateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.integration.camel.banksimulator.BankSimulatorRoutes;
import com.integration.camel.banksimulator.SimulatorProperties;
import com.integration.camel.banksimulator.SimulatorRouteConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * End-to-end flows through the gateway's HTTP API against the SNAP bank simulator (BNI profile), with
 * freshly generated keys: real signatures, tokens, timeouts and callbacks, no mocks.
 */
@CamelSpringBootTest
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"platform.console.enabled=false"})
class PaymentGatewayFlowTest {

    static final int PORT = freePort();
    static final String BASE = "http://localhost:" + PORT;
    static final String API_KEY = "test-api-key";
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    @TestConfiguration
    @Import({BankSimulatorRoutes.class, SimulatorRouteConfiguration.class})
    @EnableConfigurationProperties(SimulatorProperties.class)
    static class WithSimulator {
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        KeyPair gateway = rsa();
        KeyPair bank = rsa();
        r.add("server.port", () -> PORT);
        r.add("payment.api-key", () -> API_KEY);
        r.add("payment.reconciliation.interval", () -> "1h");
        r.add("payment.reconciliation.first-check-after", () -> "0s");
        r.add("payment.banks.bni.enabled", () -> "true");
        r.add("payment.banks.bni.base-url", () -> BASE + "/bni");
        r.add("payment.banks.bni.client-key", () -> "gateway-client");
        r.add("payment.banks.bni.client-secret", () -> "outbound-secret");
        r.add("payment.banks.bni.private-key", () -> pem("PRIVATE KEY", gateway.getPrivate().getEncoded()));
        r.add("payment.banks.bni.partner-id", () -> "PARTNER1");
        r.add("payment.banks.bni.channel-id", () -> "95221");
        r.add("payment.banks.bni.response-timeout", () -> "1s");
        r.add("payment.banks.bni.va-partner-service-id", () -> "98829");
        r.add("payment.banks.bni.inbound.client-key", () -> "bni-client");
        r.add("payment.banks.bni.inbound.client-secret", () -> "inbound-secret");
        r.add("payment.banks.bni.inbound.public-key", () -> pem("PUBLIC KEY", bank.getPublic().getEncoded()));
        r.add("simulator.slow-delay", () -> "2s");
        r.add("simulator.partner.client-key", () -> "gateway-client");
        r.add("simulator.partner.client-secret", () -> "outbound-secret");
        r.add("simulator.partner.public-key", () -> pem("PUBLIC KEY", gateway.getPublic().getEncoded()));
        r.add("simulator.callback.gateway-url", () -> BASE + "/inbound/bni");
        r.add("simulator.callback.client-key", () -> "bni-client");
        r.add("simulator.callback.client-secret", () -> "inbound-secret");
        r.add("simulator.callback.private-key", () -> pem("PRIVATE KEY", bank.getPrivate().getEncoded()));
    }

    @Test
    void intrabankTransferSucceedsAndIsIdempotent() throws Exception {
        String body = transfer("T-INTRA-1", "009", null, "150000.00");
        HttpResponse<String> first = post("/api/payments/v1/transfers", body);
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode t = JSON.readTree(first.body());
        assertThat(t.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(t.path("type").asString()).isEqualTo("INTRABANK");
        assertThat(t.path("bankResponseCode").asString()).isEqualTo("2001700");
        assertThat(t.path("bankReference").asString()).startsWith("BNI");

        // Same reference and content: stored result, bank not contacted again (it would answer 409).
        HttpResponse<String> replay = post("/api/payments/v1/transfers", body);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(replay.body()).path("bankReference").asString())
                .isEqualTo(t.path("bankReference").asString());

        // Same reference, different content: refused.
        HttpResponse<String> conflict = post("/api/payments/v1/transfers",
                transfer("T-INTRA-1", "009", null, "999.00"));
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("DUPLICATE_REFERENCE");
    }

    @Test
    void interbankRejectionIsFinal() throws Exception {
        HttpResponse<String> res = post("/api/payments/v1/transfers", transfer("T-INTER-NSF", "014", "Budi", "100.13"));
        JsonNode t = JSON.readTree(res.body());
        assertThat(t.path("type").asString()).isEqualTo("INTERBANK");
        assertThat(t.path("status").asString()).isEqualTo("FAILED");
        assertThat(t.path("bankResponseCode").asString()).isEqualTo("4031814");
    }

    @Test
    void interbankNeedsBeneficiaryName() throws Exception {
        HttpResponse<String> res = post("/api/payments/v1/transfers", transfer("T-INTER-NONAME", "014", null, "10.00"));
        assertThat(res.statusCode()).isEqualTo(400);
        assertThat(res.body()).contains("beneficiaryName");
    }

    @Test
    void timeoutIsUnknownAndResolvedByStatusInquiryWithoutResending() throws Exception {
        HttpResponse<String> res = post("/api/payments/v1/transfers", transfer("T-SLOW-1", "014", "Siti", "500.77"));
        assertThat(res.statusCode()).isEqualTo(201);
        assertThat(JSON.readTree(res.body()).path("status").asString()).isEqualTo("UNKNOWN");

        Thread.sleep(2500); // let the simulated bank finish processing
        JsonNode resolved = JSON.readTree(get("/api/payments/v1/transfers/T-SLOW-1?refresh=true").body());
        assertThat(resolved.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(resolved.path("statusChecks").asInt()).isEqualTo(1);
    }

    @Test
    void acceptedTransferStaysPendingUntilTheBankConfirms() throws Exception {
        JsonNode t = JSON.readTree(post("/api/payments/v1/transfers",
                transfer("T-PENDING-1", "009", null, "75.55")).body());
        assertThat(t.path("status").asString()).isEqualTo("PENDING");
        JsonNode second = JSON.readTree(get("/api/payments/v1/transfers/T-PENDING-1?refresh=true").body());
        assertThat(second.path("status").asString()).isEqualTo("PENDING");
        JsonNode third = JSON.readTree(get("/api/payments/v1/transfers/T-PENDING-1?refresh=true").body());
        assertThat(third.path("status").asString()).isEqualTo("SUCCESS");
    }

    @Test
    void bankErrorIsUnknownAndNotFoundStaysUnknownDuringGracePeriod() throws Exception {
        JsonNode t = JSON.readTree(post("/api/payments/v1/transfers",
                transfer("T-ERR-1", "009", null, "20.44")).body());
        assertThat(t.path("status").asString()).isEqualTo("UNKNOWN");
        JsonNode checked = JSON.readTree(get("/api/payments/v1/transfers/T-ERR-1?refresh=true").body());
        assertThat(checked.path("status").asString()).isEqualTo("UNKNOWN");
        assertThat(checked.path("bankResponseMessage").asString()).contains("latestTransactionStatus=07");
    }

    @Test
    void virtualAccountIsCreatedPaidOnceAndRejectsWrongAmounts() throws Exception {
        HttpResponse<String> created = post("/api/payments/v1/virtual-accounts", """
                {"bank":"bni","clientReferenceId":"INV-1001","customerNo":"0000001001","name":"PT Contoh",
                 "amount":250000,"expiresAt":"2099-01-01T00:00:00+07:00"}""");
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode va = JSON.readTree(created.body());
        assertThat(va.path("status").asString()).isEqualTo("PENDING");
        String vaNo = va.path("virtualAccountNo").asString();
        assertThat(vaNo).isEqualTo("988290000001001");

        JsonNode wrong = JSON.readTree(post("/sim/va-payments",
                "{\"virtualAccountNo\":\"" + vaNo + "\",\"amount\":\"1000\"}").body());
        assertThat(wrong.path("gatewayResponse").path("responseCode").asString()).isEqualTo("4042513");

        JsonNode paid = JSON.readTree(post("/sim/va-payments",
                "{\"virtualAccountNo\":\"" + vaNo + "\",\"paymentRequestId\":\"PAY-1\"}").body());
        assertThat(paid.path("gatewayStatus").asInt()).isEqualTo(200);
        assertThat(paid.path("gatewayResponse").path("responseCode").asString()).isEqualTo("2002500");

        JsonNode again = JSON.readTree(post("/sim/va-payments",
                "{\"virtualAccountNo\":\"" + vaNo + "\",\"paymentRequestId\":\"PAY-1\"}").body());
        assertThat(again.path("gatewayResponse").path("responseCode").asString()).isEqualTo("2002500");

        JsonNode other = JSON.readTree(post("/sim/va-payments",
                "{\"virtualAccountNo\":\"" + vaNo + "\",\"paymentRequestId\":\"PAY-2\"}").body());
        assertThat(other.path("gatewayResponse").path("responseCode").asString()).isEqualTo("4042514");

        JsonNode state = JSON.readTree(get("/api/payments/v1/virtual-accounts/INV-1001").body());
        assertThat(state.path("status").asString()).isEqualTo("SUCCESS");
        assertThat(state.path("paymentRequestId").asString()).isEqualTo("PAY-1");
    }

    @Test
    void inboundCallsWithoutValidSignatureAreRejected() throws Exception {
        HttpResponse<String> res = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/inbound/bni/v1.0/access-token/b2b"))
                .header("Content-Type", "application/json")
                .header("X-CLIENT-KEY", "bni-client")
                .header("X-TIMESTAMP", "2026-10-02T10:00:00+07:00")
                .header("X-SIGNATURE", "bm90LWEtc2lnbmF0dXJl")
                .POST(HttpRequest.BodyPublishers.ofString("{\"grantType\":\"client_credentials\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(res.statusCode()).isIn(400, 401);

        HttpResponse<String> payment = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/inbound/bni/v1.0/transfer-va/payment"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer forged")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(payment.statusCode()).isEqualTo(401);
        assertThat(payment.body()).contains("4012501");
    }

    @Test
    void apiRequiresKeyAndKnownBank() throws Exception {
        HttpResponse<String> noKey = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + "/api/payments/v1/banks")).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(noKey.statusCode()).isEqualTo(401);
        assertThat(get("/api/payments/v1/banks").body()).contains("\"code\":\"bni\"", "VIRTUAL_ACCOUNT");

        HttpResponse<String> unknown = post("/api/payments/v1/transfers",
                transfer("T-UNKNOWN-BANK", "009", null, "1.00").replace("\"bni\"", "\"xyz\""));
        assertThat(unknown.statusCode()).isEqualTo(400);
        // The API key the caller sent must not be echoed back.
        assertThat(unknown.headers().firstValue("X-Api-Key")).isEmpty();
    }

    // ------------------------------------------------------------------ helpers

    static String transfer(String ref, String bankCode, String name, String amount) {
        return """
                {"bank":"bni","clientReferenceId":"%s","sourceAccount":"1234567890","beneficiaryBankCode":"%s",
                 "beneficiaryAccount":"9876543210",%s"amount":%s,"remark":"invoice %s"}"""
                .formatted(ref, bankCode, name == null ? "" : "\"beneficiaryName\":\"" + name + "\",", amount, ref);
    }

    static HttpResponse<String> post(String path, String body) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path))
                .header("Content-Type", "application/json").header("X-Api-Key", API_KEY)
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(BASE + path)).header("X-Api-Key", API_KEY).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static KeyPair rsa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        return g.generateKeyPair();
    }

    static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der)
                + "\n-----END " + type + "-----";
    }

    static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
