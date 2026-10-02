package com.integration.camel.platform;

import java.util.Map;

/**
 * A service's settings as stored in the management console.
 *
 * @param logLevels  logger name to level, applied live (no restart); "ROOT" is the root logger
 * @param properties Spring properties applied at start-up (a restart is needed to change them)
 */
public record ConsoleConfig(String service, long version, Map<String, String> logLevels,
                            Map<String, String> properties) {

    public ConsoleConfig {
        logLevels = logLevels == null ? Map.of() : Map.copyOf(logLevels);
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
