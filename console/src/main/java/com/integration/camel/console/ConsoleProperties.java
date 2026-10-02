package com.integration.camel.console;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("console")
public record ConsoleProperties(String dataDir, String registry, String lokiUrl, String grafanaUrl,
                                String dashboardUid, Duration staleAfter, String writeToken) {

    public boolean writeProtected() {
        return writeToken != null && !writeToken.isBlank();
    }
}
