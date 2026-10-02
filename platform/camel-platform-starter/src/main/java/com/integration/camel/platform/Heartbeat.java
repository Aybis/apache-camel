package com.integration.camel.platform;

import java.util.List;

/** What a running instance reports to the console on every poll. */
public record Heartbeat(String instance, String environment, String domain, String version,
                        String camelVersion, String status, long appliedConfigVersion,
                        long startedAtEpochMs, List<RouteState> routes) {

    public record RouteState(String id, String status, String from) {
    }
}
