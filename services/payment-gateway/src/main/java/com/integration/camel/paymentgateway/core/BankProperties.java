package com.integration.camel.paymentgateway.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Settings for one bank, under {@code payment.banks.<code>}. The fields cover SNAP BI banks; an adapter
 * for a proprietary API reads what it needs from {@link #getExtra()}.
 */
public class BankProperties {

    private boolean enabled;
    /** e.g. https://api.bank.example; the SNAP paths are appended. */
    private String baseUrl;
    /** Bank Indonesia bank code, e.g. 009. Overrides the adapter's default. */
    private String bankIdCode;

    // Outbound (gateway -> bank) credentials, issued by the bank at onboarding.
    /** X-CLIENT-KEY (the client id). */
    private String clientKey;
    /** HMAC-SHA512 key for transaction signatures. Secret: inject from the environment. */
    private String clientSecret;
    /** Our RSA private key (PKCS#8 PEM) for the access-token signature. Secret: inject from the environment. */
    private String privateKey;
    /** X-PARTNER-ID. */
    private String partnerId;
    /** CHANNEL-ID. */
    private String channelId;

    private Duration connectTimeout = Duration.ofSeconds(5);
    /** Longer than the bank's own processing timeout, so we rarely end up in UNKNOWN. */
    private Duration responseTimeout = Duration.ofSeconds(30);

    /** Virtual accounts: the prefix (partnerServiceId / company code) the bank assigned to us. */
    private String vaPartnerServiceId;

    /** Inbound (bank -> gateway) credentials, for VA payment notifications. */
    private Inbound inbound = new Inbound();

    /** Per-operation path overrides, e.g. {@code transfer-intrabank: /v1.0/transfer-intrabank}. */
    private Map<String, String> paths = new LinkedHashMap<>();
    /** Bank-specific values used by an adapter (e.g. fixed additionalInfo fields). */
    private Map<String, String> extra = new LinkedHashMap<>();

    public static class Inbound {
        /** X-CLIENT-KEY the bank sends when it requests an access token from us. */
        private String clientKey;
        /** Secret we gave the bank for its transaction signatures. Secret: inject from the environment. */
        private String clientSecret;
        /** The bank's RSA public key (X.509 PEM) for verifying its access-token signature. */
        private String publicKey;
        private Duration tokenTtl = Duration.ofMinutes(15);
        /** Accepted clock difference on X-TIMESTAMP. */
        private Duration maxClockSkew = Duration.ofMinutes(5);

        public String getClientKey() { return clientKey; }
        public void setClientKey(String clientKey) { this.clientKey = clientKey; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
        public Duration getTokenTtl() { return tokenTtl; }
        public void setTokenTtl(Duration tokenTtl) { this.tokenTtl = tokenTtl; }
        public Duration getMaxClockSkew() { return maxClockSkew; }
        public void setMaxClockSkew(Duration maxClockSkew) { this.maxClockSkew = maxClockSkew; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getBankIdCode() { return bankIdCode; }
    public void setBankIdCode(String bankIdCode) { this.bankIdCode = bankIdCode; }
    public String getClientKey() { return clientKey; }
    public void setClientKey(String clientKey) { this.clientKey = clientKey; }
    public String getClientSecret() { return clientSecret; }
    public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    public String getPartnerId() { return partnerId; }
    public void setPartnerId(String partnerId) { this.partnerId = partnerId; }
    public String getChannelId() { return channelId; }
    public void setChannelId(String channelId) { this.channelId = channelId; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }
    public Duration getResponseTimeout() { return responseTimeout; }
    public void setResponseTimeout(Duration responseTimeout) { this.responseTimeout = responseTimeout; }
    public String getVaPartnerServiceId() { return vaPartnerServiceId; }
    public void setVaPartnerServiceId(String vaPartnerServiceId) { this.vaPartnerServiceId = vaPartnerServiceId; }
    public Inbound getInbound() { return inbound; }
    public void setInbound(Inbound inbound) { this.inbound = inbound; }
    public Map<String, String> getPaths() { return paths; }
    public void setPaths(Map<String, String> paths) { this.paths = paths; }
    public Map<String, String> getExtra() { return extra; }
    public void setExtra(Map<String, String> extra) { this.extra = extra; }
}
