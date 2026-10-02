package com.integration.camel.console;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.integration.camel.console.Model.AuditEntry;
import com.integration.camel.console.Model.ConfigChange;
import com.integration.camel.console.Model.Heartbeat;
import com.integration.camel.console.Model.InstanceView;
import com.integration.camel.console.Model.ServiceConfig;
import com.integration.camel.console.Model.ServiceEntry;
import com.integration.camel.console.Model.ServiceView;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final ConfigStore store;
    private final HeartbeatRegistry heartbeats;
    private final LokiClient loki;
    private final ConsoleProperties properties;

    public ApiController(ConfigStore store, HeartbeatRegistry heartbeats, LokiClient loki,
                         ConsoleProperties properties) {
        this.store = store;
        this.heartbeats = heartbeats;
        this.loki = loki;
        this.properties = properties;
    }

    @GetMapping("/settings")
    public Map<String, Object> settings() {
        return Map.of("grafanaUrl", properties.grafanaUrl(),
                "dashboardUid", properties.dashboardUid(),
                "writeProtected", properties.writeProtected(),
                "staleAfterSeconds", properties.staleAfter().toSeconds());
    }

    @GetMapping("/services")
    public List<ServiceView> services() {
        return store.all().stream().map(this::view).toList();
    }

    @GetMapping("/services/{name}")
    public ResponseEntity<ServiceView> service(@PathVariable String name) {
        return ResponseEntity.of(store.find(name).map(this::view));
    }

    /** Pulled by services at start-up and on every poll. 404 means "no settings; use your own". */
    @GetMapping("/services/{name}/config")
    public ResponseEntity<ServiceConfig> config(@PathVariable String name) {
        return ResponseEntity.of(store.find(name).map(ServiceEntry::config));
    }

    @PutMapping("/services/{name}/config")
    public ServiceConfig updateConfig(@PathVariable String name, @RequestBody ConfigChange change,
                                      @RequestHeader(value = "X-Console-Token", required = false) String token) {
        requireWriteToken(token);
        return store.update(name, change);
    }

    @PostMapping("/services/{name}/heartbeat")
    public ResponseEntity<Void> heartbeat(@PathVariable String name, @RequestBody Heartbeat heartbeat) {
        if (!name.matches("[a-z][a-z0-9-]{1,63}")) {
            return ResponseEntity.badRequest().build();
        }
        store.registerIfAbsent(name, heartbeat.domain());
        heartbeats.record(name, heartbeat);
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/services/{name}/logs")
    public Map<String, Object> logs(@PathVariable String name,
                                    @RequestParam(required = false) List<String> level,
                                    @RequestParam(required = false) String search,
                                    @RequestParam(required = false) String correlationId,
                                    @RequestParam(defaultValue = "60") int minutes,
                                    @RequestParam(defaultValue = "200") int limit) throws Exception {
        if (store.find(name).isEmpty()) {
            throw new NotFound("Unknown service " + name);
        }
        String query = LokiClient.logQl(name, level, search, correlationId);
        int boundedMinutes = Math.clamp(minutes, 1, 7 * 24 * 60);
        int boundedLimit = Math.clamp(limit, 1, 1000);
        return Map.of("query", query, "lines", loki.query(query, Duration.ofMinutes(boundedMinutes), boundedLimit));
    }

    @GetMapping("/audit")
    public List<AuditEntry> audit(@RequestParam(required = false) String service,
                                  @RequestParam(defaultValue = "100") int limit) {
        return store.audit(service, Math.clamp(limit, 1, 1000));
    }

    private ServiceView view(ServiceEntry entry) {
        List<InstanceView> instances = heartbeats.instances(entry.name());
        long version = entry.config().version();
        boolean pending = instances.stream().anyMatch(i -> !i.stale() && i.heartbeat().appliedConfigVersion() < version);
        return new ServiceView(entry.name(), entry.domain(), entry.description(), entry.port(), entry.localOnly(),
                HeartbeatRegistry.status(instances), version, pending, instances);
    }

    private void requireWriteToken(String token) {
        if (properties.writeProtected() && !properties.writeToken().equals(token)) {
            throw new Forbidden("A valid X-Console-Token header is required to change settings");
        }
    }

    static class NotFound extends RuntimeException {
        NotFound(String message) { super(message); }
    }

    static class Forbidden extends RuntimeException {
        Forbidden(String message) { super(message); }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(NotFound.class)
    ResponseEntity<Map<String, String>> notFound(NotFound e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(Forbidden.class)
    ResponseEntity<Map<String, String>> forbidden(Forbidden e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(IllegalStateException.class)
    ResponseEntity<Map<String, String>> upstream(IllegalStateException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(java.net.ConnectException.class)
    ResponseEntity<Map<String, String>> lokiDown(java.net.ConnectException e) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Loki is unreachable at " + properties.lokiUrl()));
    }
}
