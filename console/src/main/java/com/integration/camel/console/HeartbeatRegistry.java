package com.integration.camel.console;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.integration.camel.console.Model.Heartbeat;
import com.integration.camel.console.Model.InstanceView;
import com.integration.camel.console.Model.Status;

/** Latest heartbeat per service instance, held in memory (rebuilt within one poll after a restart). */
@Component
public class HeartbeatRegistry {

    private record Received(Heartbeat heartbeat, Instant at) {
    }

    private final Map<String, Map<String, Received>> byService = new ConcurrentHashMap<>();
    private final ConsoleProperties properties;

    public HeartbeatRegistry(ConsoleProperties properties) {
        this.properties = properties;
    }

    public void record(String service, Heartbeat heartbeat) {
        byService.computeIfAbsent(service, s -> new ConcurrentHashMap<>())
                .put(heartbeat.instance(), new Received(heartbeat, Instant.now()));
    }

    public List<InstanceView> instances(String service) {
        Instant staleBefore = Instant.now().minus(properties.staleAfter());
        Instant forgetBefore = Instant.now().minus(properties.staleAfter().multipliedBy(10));
        Map<String, Received> instances = byService.getOrDefault(service, Map.of());
        instances.values().removeIf(r -> r.at().isBefore(forgetBefore));
        return instances.values().stream()
                .sorted(Comparator.comparing(r -> r.heartbeat().instance()))
                .map(r -> new InstanceView(r.heartbeat(), r.at(), r.at().isBefore(staleBefore)))
                .toList();
    }

    public static Status status(List<InstanceView> instances) {
        if (instances.isEmpty()) {
            return Status.UNKNOWN;
        }
        if (instances.stream().anyMatch(i -> !i.stale() && "UP".equals(i.heartbeat().status()))) {
            return Status.UP;
        }
        if (instances.stream().allMatch(i -> "STOPPING".equals(i.heartbeat().status()))) {
            return Status.DOWN;
        }
        return Status.STALE;
    }
}
