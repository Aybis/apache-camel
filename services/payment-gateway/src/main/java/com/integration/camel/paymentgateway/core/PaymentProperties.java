package com.integration.camel.paymentgateway.core;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code payment.*} settings. Connection settings live in Git (application.yml); secrets come from
 * environment variables or a vault, never from YAML or the management console.
 */
@ConfigurationProperties("payment")
public class PaymentProperties {

    /** When set, API callers must send it in the {@code X-Api-Key} header. Empty disables the check. */
    private String apiKey = "";
    /** Where final payment events are sent (any Camel endpoint URI, e.g. a JMS queue). */
    private String eventsUri = "log:payment-events?level=INFO";
    private Reconciliation reconciliation = new Reconciliation();
    private Map<String, BankProperties> banks = new LinkedHashMap<>();

    public static class Reconciliation {
        /** How often PENDING and UNKNOWN transfers are checked with the bank. */
        private Duration interval = Duration.ofSeconds(30);
        /** Wait this long after sending before the first status check. */
        private Duration firstCheckAfter = Duration.ofSeconds(10);
        /** After this many checks without a final answer the transfer is flagged for manual follow-up. */
        private int maxChecks = 20;
        /**
         * "Transaction not found" is treated as FAILED only once the transfer is older than this,
         * because some banks register the transaction asynchronously.
         */
        private Duration notFoundGrace = Duration.ofMinutes(10);

        public Duration getInterval() { return interval; }
        public void setInterval(Duration interval) { this.interval = interval; }
        public Duration getFirstCheckAfter() { return firstCheckAfter; }
        public void setFirstCheckAfter(Duration firstCheckAfter) { this.firstCheckAfter = firstCheckAfter; }
        public int getMaxChecks() { return maxChecks; }
        public void setMaxChecks(int maxChecks) { this.maxChecks = maxChecks; }
        public Duration getNotFoundGrace() { return notFoundGrace; }
        public void setNotFoundGrace(Duration notFoundGrace) { this.notFoundGrace = notFoundGrace; }
    }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getEventsUri() { return eventsUri; }
    public void setEventsUri(String eventsUri) { this.eventsUri = eventsUri; }
    public Reconciliation getReconciliation() { return reconciliation; }
    public void setReconciliation(Reconciliation reconciliation) { this.reconciliation = reconciliation; }
    public Map<String, BankProperties> getBanks() { return banks; }
    public void setBanks(Map<String, BankProperties> banks) { this.banks = banks; }

    public BankProperties bank(String code) {
        BankProperties p = banks.get(code);
        if (p == null) {
            throw new IllegalStateException("No configuration under payment.banks." + code);
        }
        return p;
    }
}
