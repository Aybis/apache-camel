package com.integration.camel.banksimulator;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.camel.Exchange;
import org.apache.camel.builder.RouteBuilder;
import org.springframework.stereotype.Component;

import com.integration.camel.platform.PlatformLog;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A SNAP BI bank (BNI profile) for local development and tests. It checks the access-token signature,
 * bearer token, HMAC signature and X-EXTERNAL-ID uniqueness the way a bank does, and keeps transfers and
 * virtual accounts in memory.
 * <p>
 * Behaviour is chosen by the cents of the amount:
 * <pre>
 *  .13  rejected: insufficient funds (403)
 *  .44  internal error (500) and the transfer is NOT recorded (status inquiry: 07 not found)
 *  .55  accepted, in progress (202); the first status inquiry says pending (03), later ones success (00)
 *  .77  processed successfully but answered after simulator.slow-delay, to provoke a client timeout
 *  else success (200)
 * </pre>
 * Test hook (not part of SNAP): {@code POST /sim/va-payments {"virtualAccountNo":"..","amount":"..."}} makes the
 * bank notify the gateway of a payment, exactly as a teller or mobile-banking payment would.
 */
@Component
public class BankSimulatorRoutes extends RouteBuilder {

    private static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private final SimulatorProperties props;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();

    private final Map<String, Instant> tokens = new ConcurrentHashMap<>();
    private final Set<String> externalIds = ConcurrentHashMap.newKeySet();
    /** partnerReferenceNo -> transfer record. */
    private final Map<String, ObjectNode> transfers = new ConcurrentHashMap<>();
    private final Map<String, Integer> statusQueries = new ConcurrentHashMap<>();
    /** trimmed virtualAccountNo -> create-va request. */
    private final Map<String, JsonNode> virtualAccounts = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong(System.currentTimeMillis() % 1_000_000_000L);

    public BankSimulatorRoutes(SimulatorProperties props, JsonMapper json) {
        this.props = props;
        this.json = json;
    }

    @Override
    public void configure() {
        String base = "platform-http:" + props.getPrefix() + "/v1.0";

        from(base + "/access-token/b2b?httpMethodRestrict=POST").routeId("bank-simulator-token").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(this::token);
        from(base + "/transfer-intrabank?httpMethodRestrict=POST").routeId("bank-simulator-intrabank").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(ex -> transfer(ex, "17"));
        from(base + "/transfer-interbank?httpMethodRestrict=POST").routeId("bank-simulator-interbank").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(ex -> transfer(ex, "18"));
        from(base + "/transfer/status?httpMethodRestrict=POST").routeId("bank-simulator-status").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(this::status);
        from(base + "/transfer-va/create-va?httpMethodRestrict=POST").routeId("bank-simulator-create-va").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(this::createVa);
        from("platform-http:/sim/va-payments?httpMethodRestrict=POST").routeId("bank-simulator-pay-va").routeConfigurationId(SimulatorRouteConfiguration.ID)
                .process(this::payVa);
    }

    // ------------------------------------------------------------------ SNAP endpoints

    private void token(Exchange ex) throws Exception {
        String clientKey = ex.getIn().getHeader("X-CLIENT-KEY", String.class);
        String timestamp = ex.getIn().getHeader("X-TIMESTAMP", String.class);
        String signature = ex.getIn().getHeader("X-SIGNATURE", String.class);
        SimulatorProperties.Partner p = props.getPartner();
        PublicKey key = p.getPublicKey() == null || p.getPublicKey().isBlank() ? null
                : SnapCrypto.publicKey(p.getPublicKey());
        if (key == null || !p.getClientKey().equals(clientKey) || timestamp == null || signature == null
                || !SnapCrypto.verifyTokenRequest(key, clientKey, timestamp, signature)) {
            reply(ex, 401, "4017300", "Unauthorized. [Signature]", null);
            return;
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        tokens.put(token, Instant.now().plusSeconds(900));
        ObjectNode res = json.createObjectNode();
        res.put("accessToken", token);
        res.put("tokenType", "Bearer");
        res.put("expiresIn", "900");
        reply(ex, 200, "2007300", "Successful", res);
    }

    /** Bearer token, signature and X-EXTERNAL-ID checks shared by every transaction endpoint. */
    private boolean authorised(Exchange ex, String service, String body) throws Exception {
        String auth = ex.getIn().getHeader("Authorization", String.class);
        String token = auth == null ? "" : auth.replaceFirst("^Bearer ", "");
        Instant expiry = tokens.get(token);
        if (expiry == null || expiry.isBefore(Instant.now())) {
            reply(ex, 401, "401" + service + "01", "Invalid Token (B2B)", null);
            return false;
        }
        String path = ex.getIn().getHeader(Exchange.HTTP_URI, String.class);
        String timestamp = ex.getIn().getHeader("X-TIMESTAMP", String.class);
        String expected = SnapCrypto.hmac(props.getPartner().getClientSecret(), "POST", path, token, body,
                timestamp == null ? "" : timestamp);
        String given = ex.getIn().getHeader("X-SIGNATURE", String.class);
        if (given == null || !MessageDigest.isEqual(expected.getBytes(), given.getBytes())) {
            reply(ex, 401, "401" + service + "00", "Unauthorized. [Signature]", null);
            return false;
        }
        String externalId = ex.getIn().getHeader("X-EXTERNAL-ID", String.class);
        if (externalId == null || !externalIds.add(OffsetDateTime.now(JAKARTA).toLocalDate() + ":" + externalId)) {
            reply(ex, 409, "409" + service + "00", "Conflict", null);
            return false;
        }
        return true;
    }

    private void transfer(Exchange ex, String service) throws Exception {
        String raw = ex.getIn().getBody(String.class);
        if (!authorised(ex, service, raw)) {
            return;
        }
        JsonNode req = json.readTree(raw);
        String ref = req.path("partnerReferenceNo").asString();
        if (transfers.containsKey(ref)) {
            reply(ex, 409, "409" + service + "01", "Duplicate partnerReferenceNo", null);
            return;
        }
        String cents = cents(req.path("amount").path("value").asString("0"));
        ObjectNode rec = json.createObjectNode();
        rec.put("referenceNo", "BNI" + sequence.incrementAndGet());
        rec.put("partnerReferenceNo", ref);
        rec.set("amount", req.get("amount"));
        rec.put("serviceCode", service);
        PlatformLog.event(log, ex, "simulator.transfer", "service", service, "reference", ref, "cents", cents);
        switch (cents) {
            case "13" -> reply(ex, 403, "403" + service + "14", "Insufficient Funds", null);
            case "44" -> reply(ex, 500, "500" + service + "01", "Internal Server Error", null);
            case "55" -> {
                rec.put("state", "pending");
                transfers.put(ref, rec);
                reply(ex, 202, "202" + service + "00", "Request In Progress", rec.deepCopy());
            }
            case "77" -> {
                rec.put("state", "success");
                transfers.put(ref, rec);
                Thread.sleep(props.getSlowDelay().toMillis());
                reply(ex, 200, "200" + service + "00", "Successful", rec.deepCopy());
            }
            default -> {
                rec.put("state", "success");
                transfers.put(ref, rec);
                reply(ex, 200, "200" + service + "00", "Successful", rec.deepCopy());
            }
        }
    }

    private void status(Exchange ex) throws Exception {
        String raw = ex.getIn().getBody(String.class);
        if (!authorised(ex, "36", raw)) {
            return;
        }
        JsonNode req = json.readTree(raw);
        String ref = req.path("originalPartnerReferenceNo").asString();
        ObjectNode rec = transfers.get(ref);
        ObjectNode res = json.createObjectNode();
        res.put("originalPartnerReferenceNo", ref);
        res.put("serviceCode", req.path("serviceCode").asString());
        if (rec == null) {
            res.put("latestTransactionStatus", "07");
            res.put("transactionStatusDesc", "Not Found");
        } else {
            res.put("originalReferenceNo", rec.path("referenceNo").asString());
            res.set("amount", rec.get("amount"));
            boolean pending = "pending".equals(rec.path("state").asString())
                    && statusQueries.merge(ref, 1, Integer::sum) <= 1;
            res.put("latestTransactionStatus", pending ? "03" : "00");
            res.put("transactionStatusDesc", pending ? "Pending" : "Success");
        }
        reply(ex, 200, "2003600", "Successful", res);
    }

    private void createVa(Exchange ex) throws Exception {
        String raw = ex.getIn().getBody(String.class);
        if (!authorised(ex, "27", raw)) {
            return;
        }
        JsonNode req = json.readTree(raw);
        String vaNo = req.path("virtualAccountNo").asString().trim();
        if (virtualAccounts.putIfAbsent(vaNo, req) != null) {
            reply(ex, 409, "4092700", "Duplicate VA Number", null);
            return;
        }
        ObjectNode res = json.createObjectNode();
        res.set("virtualAccountData", req);
        reply(ex, 200, "2002700", "Successful", res);
    }

    // ------------------------------------------------------------------ bank -> gateway

    /** Simulates a customer paying a virtual account: the bank notifies the gateway (SNAP service 25). */
    private void payVa(Exchange ex) throws Exception {
        JsonNode in = json.readTree(ex.getIn().getBody(String.class));
        String vaNo = in.path("virtualAccountNo").asString();
        JsonNode va = virtualAccounts.get(vaNo);
        String amount = in.path("amount").asString(
                va == null ? "0" : va.path("totalAmount").path("value").asString("0"));
        SimulatorProperties.Callback cb = props.getCallback();
        String base = cb.getGatewayUrl();
        String basePath = URI.create(base).getRawPath();

        // 1. Access token from the gateway, signed with the bank's private key.
        PrivateKey key = SnapCrypto.privateKey(cb.getPrivateKey());
        String ts = OffsetDateTime.now(JAKARTA).truncatedTo(ChronoUnit.SECONDS).format(TS);
        HttpResponse<String> tokenRes = http.send(HttpRequest.newBuilder(URI.create(base + "/v1.0/access-token/b2b"))
                .header("Content-Type", "application/json")
                .header("X-TIMESTAMP", ts)
                .header("X-CLIENT-KEY", cb.getClientKey())
                .header("X-SIGNATURE", SnapCrypto.signTokenRequest(key, cb.getClientKey(), ts))
                .POST(HttpRequest.BodyPublishers.ofString("{\"grantType\":\"client_credentials\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        if (tokenRes.statusCode() != 200) {
            ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 502);
            ex.getMessage().setBody("{\"step\":\"token\",\"gatewayStatus\":" + tokenRes.statusCode()
                    + ",\"gatewayBody\":" + json.writeValueAsString(tokenRes.body()) + "}");
            return;
        }
        String token = json.readTree(tokenRes.body()).path("accessToken").asString();

        // 2. Payment notification, HMAC-signed.
        ObjectNode n = json.createObjectNode();
        n.put("partnerServiceId", va == null ? "" : va.path("partnerServiceId").asString());
        n.put("customerNo", va == null ? "" : va.path("customerNo").asString());
        n.put("virtualAccountNo", va == null ? vaNo : va.path("virtualAccountNo").asString());
        n.put("virtualAccountName", va == null ? "" : va.path("virtualAccountName").asString());
        n.put("paymentRequestId", in.path("paymentRequestId").asString("PAY" + sequence.incrementAndGet()));
        ObjectNode paid = n.putObject("paidAmount");
        paid.put("value", new BigDecimal(amount).setScale(2).toPlainString());
        paid.put("currency", "IDR");
        n.put("trxDateTime", OffsetDateTime.now(JAKARTA).truncatedTo(ChronoUnit.SECONDS).format(TS));
        String body = json.writeValueAsString(n);
        String path = basePath + "/v1.0/transfer-va/payment";
        String ts2 = OffsetDateTime.now(JAKARTA).truncatedTo(ChronoUnit.SECONDS).format(TS);
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(base + "/v1.0/transfer-va/payment"))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("X-TIMESTAMP", ts2)
                .header("X-SIGNATURE", SnapCrypto.hmac(cb.getClientSecret(), "POST", path, token, body, ts2))
                .header("X-PARTNER-ID", "BNI")
                .header("X-EXTERNAL-ID", String.valueOf(sequence.incrementAndGet()))
                .header("CHANNEL-ID", "95221")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        ex.getMessage().removeHeaders("*");
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
        ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        ex.getMessage().setBody("{\"notification\":" + body + ",\"gatewayStatus\":" + res.statusCode()
                + ",\"gatewayResponse\":" + res.body() + "}");
    }

    // ------------------------------------------------------------------ helpers

    static void reply(JsonMapper json, Exchange ex, int http, String code, String message) {
        ObjectNode res = json.createObjectNode();
        res.put("responseCode", code);
        res.put("responseMessage", message);
        ex.getMessage().removeHeaders("*");
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, http);
        ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        ex.getMessage().setBody(json.writeValueAsString(res));
    }

    private void reply(Exchange ex, int http, String code, String message, ObjectNode extra) {
        ObjectNode res = json.createObjectNode();
        res.put("responseCode", code);
        res.put("responseMessage", message);
        if (extra != null) {
            res.setAll(extra);
        }
        ex.getMessage().removeHeaders("*");
        ex.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, http);
        ex.getMessage().setHeader(Exchange.CONTENT_TYPE, "application/json");
        ex.getMessage().setBody(json.writeValueAsString(res));
    }

    private static String cents(String value) {
        int dot = value.indexOf('.');
        return dot < 0 ? "00" : (value.substring(dot + 1) + "00").substring(0, 2);
    }
}
