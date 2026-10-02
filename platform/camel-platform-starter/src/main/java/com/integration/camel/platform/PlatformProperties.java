package com.integration.camel.platform;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings under {@code platform.*}. Defaults live in config/global/monitoring.yml.
 */
@ConfigurationProperties("platform")
public class PlatformProperties {

    private String environment = "local";
    private String domain = "unassigned";
    private final Console console = new Console();
    private final Correlation correlation = new Correlation();
    private final ErrorHandling errorHandling = new ErrorHandling();

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }
    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }
    public Console getConsole() { return console; }
    public Correlation getCorrelation() { return correlation; }
    public ErrorHandling getErrorHandling() { return errorHandling; }

    public static class Console {
        private boolean enabled = true;
        private String url = "http://localhost:8090";
        private Duration pollInterval = Duration.ofSeconds(15);
        private Duration timeout = Duration.ofSeconds(2);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public Duration getPollInterval() { return pollInterval; }
        public void setPollInterval(Duration pollInterval) { this.pollInterval = pollInterval; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
    }

    public static class Correlation {
        private String header = "X-Correlation-Id";

        public String getHeader() { return header; }
        public void setHeader(String header) { this.header = header; }
    }

    public static class ErrorHandling {
        private int maximumRedeliveries = 3;
        private long redeliveryDelay = 1000;
        private double backoffMultiplier = 2.0;
        /** Empty: log the failure (no body) to logger platform.dead-letter. Set for a real dead letter queue. */
        private String deadLetterUri;
        /** Include the (masked, truncated) body in the dead letter log line. Off: bodies carry personal data. */
        private boolean logBody;

        public int getMaximumRedeliveries() { return maximumRedeliveries; }
        public void setMaximumRedeliveries(int maximumRedeliveries) { this.maximumRedeliveries = maximumRedeliveries; }
        public long getRedeliveryDelay() { return redeliveryDelay; }
        public void setRedeliveryDelay(long redeliveryDelay) { this.redeliveryDelay = redeliveryDelay; }
        public double getBackoffMultiplier() { return backoffMultiplier; }
        public void setBackoffMultiplier(double backoffMultiplier) { this.backoffMultiplier = backoffMultiplier; }
        public String getDeadLetterUri() { return deadLetterUri; }
        public void setDeadLetterUri(String deadLetterUri) { this.deadLetterUri = deadLetterUri; }
        public boolean isLogBody() { return logBody; }
        public void setLogBody(boolean logBody) { this.logBody = logBody; }
    }
}
