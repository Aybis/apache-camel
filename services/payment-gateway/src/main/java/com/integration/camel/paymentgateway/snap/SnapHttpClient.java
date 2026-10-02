package com.integration.camel.paymentgateway.snap;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

import javax.net.ssl.SSLHandshakeException;

import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.component.http.HttpClientConfigurer;
import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.HttpHostConnectException;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.integration.camel.paymentgateway.core.BankProperties;
import com.integration.camel.platform.MdcScope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Calls one bank's SNAP BI API through the Camel HTTP component: obtains and caches the B2B access token,
 * signs every request, applies the bank's connect and response timeouts, and classifies failures as
 * "not sent" or "outcome unknown". One instance per bank.
 */
public class SnapHttpClient {

    public static final String TOKEN_PATH = "/v1.0/access-token/b2b";
    private static final Logger LOG = LoggerFactory.getLogger(SnapHttpClient.class);
    /** Refresh the token this long before the bank says it expires. */
    private static final long TOKEN_MARGIN_SECONDS = 60;

    private final String bank;
    private final BankProperties props;
    private final ProducerTemplate producer;
    private final JsonMapper json;
    private final PrivateKey privateKey;
    private final String endpointOptions;
    private final String tokenPath;

    private volatile String accessToken;
    private volatile Instant accessTokenExpiry = Instant.EPOCH;

    public SnapHttpClient(String bank, BankProperties props, CamelContext camel, ProducerTemplate producer,
                          JsonMapper json, String tokenPath) {
        this.bank = bank;
        this.props = props;
        this.producer = producer;
        this.json = json;
        this.tokenPath = tokenPath;
        this.privateKey = SnapSignature.privateKey(require(props.getPrivateKey(), "private-key"));
        require(props.getClientKey(), "client-key");
        require(props.getClientSecret(), "client-secret");
        require(props.getBaseUrl(), "base-url");

        // Per-bank timeouts: the Camel HTTP component only has component-wide timeouts, so each bank gets
        // its own client configuration through an HttpClientConfigurer bound in the registry.
        String configurerName = "snapHttpClientConfigurer-" + bank;
        HttpClientConfigurer configurer = builder -> builder
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setDefaultConnectionConfig(ConnectionConfig.custom()
                                .setConnectTimeout(Timeout.of(props.getConnectTimeout()))
                                .setSocketTimeout(Timeout.of(props.getResponseTimeout()))
                                .build())
                        .build())
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setResponseTimeout(Timeout.of(props.getResponseTimeout()))
                        .build());
        camel.getRegistry().bind(configurerName, HttpClientConfigurer.class, configurer);
        this.endpointOptions = "?throwExceptionOnFailure=false&httpClientConfigurer=#" + configurerName;
    }

    /**
     * POSTs a SNAP transaction. Re-authorises once if the bank rejects the token (that rejection happens
     * before any processing, so repeating it with the same X-EXTERNAL-ID is safe); every other outcome is
     * returned to the adapter.
     *
     * @param externalId X-EXTERNAL-ID chosen by the caller (see {@link #newExternalId()}), so it is known
     *                   even when the call times out and the transfer has to be looked up later
     */
    public SnapResponse post(String path, ObjectNode body, String externalId) {
        String payload = json.writeValueAsString(body);
        SnapResponse r = send(path, payload, token(false), externalId);
        if (r.httpStatus() == 401 && r.responseCode() != null && r.responseCode().startsWith("401")) {
            LOG.warn("Bank {} rejected the access token ({} {}); re-authorising once", bank, r.responseCode(),
                    r.responseMessage());
            r = send(path, payload, token(true), externalId);
        }
        return r;
    }

    private SnapResponse send(String path, String payload, String token, String externalId) {
        String timestamp = SnapSignature.timestamp();
        // SNAP signs the URL path as the bank sees it, so a base URL with a path prefix is included.
        String signedPath = URI.create(props.getBaseUrl() + path).getRawPath();
        String signature = SnapSignature.signTransaction(props.getClientSecret(), "POST", signedPath, token,
                payload, timestamp);
        Exchange result = MdcScope.preserving(() -> producer.request(props.getBaseUrl() + path + endpointOptions, ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
            ex.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
            ex.getIn().setHeader("Authorization", "Bearer " + token);
            ex.getIn().setHeader("X-TIMESTAMP", timestamp);
            ex.getIn().setHeader("X-SIGNATURE", signature);
            ex.getIn().setHeader("X-PARTNER-ID", props.getPartnerId());
            ex.getIn().setHeader("X-EXTERNAL-ID", externalId);
            ex.getIn().setHeader("CHANNEL-ID", props.getChannelId());
            ex.getIn().setBody(payload);
        }));
        return toResponse(path, result, externalId);
    }

    private synchronized String token(boolean forceRefresh) {
        if (!forceRefresh && accessToken != null && Instant.now().isBefore(accessTokenExpiry)) {
            return accessToken;
        }
        String timestamp = SnapSignature.timestamp();
        String signature = SnapSignature.signAccessToken(privateKey, props.getClientKey(), timestamp);
        Exchange result = MdcScope.preserving(() -> producer.request(props.getBaseUrl() + tokenPath + endpointOptions, ex -> {
            ex.getIn().setHeader(Exchange.HTTP_METHOD, "POST");
            ex.getIn().setHeader(Exchange.CONTENT_TYPE, "application/json");
            ex.getIn().setHeader("X-TIMESTAMP", timestamp);
            ex.getIn().setHeader("X-CLIENT-KEY", props.getClientKey());
            ex.getIn().setHeader("X-SIGNATURE", signature);
            ex.getIn().setBody("{\"grantType\":\"client_credentials\"}");
        }));
        SnapResponse r = toResponse(tokenPath, result, null);
        String token = r.text("accessToken");
        if (!r.isSuccess() || token == null) {
            // Without a token nothing was sent, so callers may treat the instruction as not sent.
            throw new BankCallException(BankCallException.Kind.NOT_SENT,
                    "Access token refused by " + bank + ": " + r.responseCode() + " " + r.responseMessage(), null);
        }
        long ttl = parseLong(r.text("expiresIn"), 900);
        accessToken = token;
        accessTokenExpiry = Instant.now().plusSeconds(Math.max(0, ttl - TOKEN_MARGIN_SECONDS));
        return token;
    }

    private SnapResponse toResponse(String path, Exchange result, String externalId) {
        if (result.getException() != null) {
            throw classify(path, result.getException());
        }
        int status = result.getMessage().getHeader(Exchange.HTTP_RESPONSE_CODE, 0, Integer.class);
        String raw = result.getMessage().getBody(String.class);
        JsonNode body;
        try {
            body = raw == null || raw.isBlank() ? json.createObjectNode() : json.readTree(raw);
        } catch (RuntimeException e) {
            body = json.createObjectNode().put("raw", raw);
        }
        JsonNode code = body.get("responseCode");
        JsonNode message = body.get("responseMessage");
        return new SnapResponse(status, code == null ? null : code.asString(),
                message == null ? null : message.asString(), body, externalId);
    }

    private BankCallException classify(String path, Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpHostConnectException || t instanceof ConnectException
                    || t instanceof ConnectTimeoutException || t instanceof UnknownHostException
                    || t instanceof NoRouteToHostException || t instanceof SSLHandshakeException) {
                return new BankCallException(BankCallException.Kind.NOT_SENT,
                        bank + " " + path + " not reachable: " + t, e);
            }
        }
        String reason = e instanceof IOException || e.getCause() instanceof IOException
                ? "no response (" + e + ")" : e.toString();
        return new BankCallException(BankCallException.Kind.OUTCOME_UNKNOWN, bank + " " + path + ": " + reason, e);
    }

    /** X-EXTERNAL-ID: numeric, unique per day per partner (SNAP); 13-digit millis + 8 random digits. */
    public static String newExternalId() {
        return System.currentTimeMillis() + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }

    private static long parseLong(String v, long fallback) {
        try {
            return v == null ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("payment.banks." + bank + "." + name + " is not set");
        }
        return value;
    }
}
