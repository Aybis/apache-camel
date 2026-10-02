package com.integration.camel.banksimulator;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** {@code simulator.*}: credentials the simulated bank shares with the gateway. Local and test use only. */
@ConfigurationProperties("simulator")
public class SimulatorProperties {

    /** URL prefix the simulated bank answers on, e.g. /bni. */
    private String prefix = "/bni";
    /** Delay applied to amounts ending in .77, to provoke a gateway timeout. */
    private Duration slowDelay = Duration.ofSeconds(35);
    private Partner partner = new Partner();
    private Callback callback = new Callback();

    /** The gateway as a client of the bank. */
    public static class Partner {
        private String clientKey;
        private String clientSecret;
        private String publicKey;

        public String getClientKey() { return clientKey; }
        public void setClientKey(String clientKey) { this.clientKey = clientKey; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getPublicKey() { return publicKey; }
        public void setPublicKey(String publicKey) { this.publicKey = publicKey; }
    }

    /** The bank calling the gateway (VA payment notifications). */
    public static class Callback {
        /** e.g. http://localhost:8102/inbound/bni */
        private String gatewayUrl = "http://localhost:8102/inbound/bni";
        private String clientKey;
        private String clientSecret;
        private String privateKey;

        public String getGatewayUrl() { return gatewayUrl; }
        public void setGatewayUrl(String gatewayUrl) { this.gatewayUrl = gatewayUrl; }
        public String getClientKey() { return clientKey; }
        public void setClientKey(String clientKey) { this.clientKey = clientKey; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String clientSecret) { this.clientSecret = clientSecret; }
        public String getPrivateKey() { return privateKey; }
        public void setPrivateKey(String privateKey) { this.privateKey = privateKey; }
    }

    public String getPrefix() { return prefix; }
    public void setPrefix(String prefix) { this.prefix = prefix; }
    public Duration getSlowDelay() { return slowDelay; }
    public void setSlowDelay(Duration slowDelay) { this.slowDelay = slowDelay; }
    public Partner getPartner() { return partner; }
    public void setPartner(Partner partner) { this.partner = partner; }
    public Callback getCallback() { return callback; }
    public void setCallback(Callback callback) { this.callback = callback; }
}
