package com.integration.camel.console;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.integration.camel.console.Model.LogLine;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a service's logs from Loki. Logs are labelled by the collector (Grafana Alloy) with
 * {@code service}, {@code level}, {@code domain} and {@code environment}; the line is ECS JSON.
 */
@Component
public class LokiClient {

    private static final Set<String> LEVELS = Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR");

    private final ConsoleProperties properties;
    private final JsonMapper mapper;
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .proxy(HttpClient.Builder.NO_PROXY)
            .build();

    public LokiClient(ConsoleProperties properties, JsonMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    /** Builds the LogQL query; also shown in the UI so users can paste it into Grafana Explore. */
    public static String logQl(String service, List<String> levels, String search, String correlationId) {
        StringBuilder q = new StringBuilder("{service=\"").append(service).append('"');
        List<String> valid = levels == null ? List.of()
                : levels.stream().map(String::toUpperCase).filter(LEVELS::contains).toList();
        if (!valid.isEmpty()) {
            q.append(", level=~\"").append(String.join("|", valid)).append('"');
        }
        q.append('}');
        if (correlationId != null && !correlationId.isBlank()) {
            q.append(" |= ").append(quote(correlationId.trim()));
        }
        if (search != null && !search.isBlank()) {
            q.append(" |= ").append(quote(search.trim()));
        }
        return q.toString();
    }

    private static String quote(String value) {
        // Backtick strings in LogQL take the value literally; strip backticks to stay inside it.
        return "`" + value.replace("`", "") + "`";
    }

    public List<LogLine> query(String logQl, Duration window, int limit) throws Exception {
        Instant end = Instant.now();
        Instant start = end.minus(window);
        String url = properties.lokiUrl() + "/loki/api/v1/query_range"
                + "?query=" + URLEncoder.encode(logQl, StandardCharsets.UTF_8)
                + "&start=" + start.toEpochMilli() + "000000"
                + "&end=" + end.toEpochMilli() + "000000"
                + "&limit=" + limit
                + "&direction=backward";
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("Loki returned HTTP " + response.statusCode() + ": " + response.body());
        }
        List<LogLine> lines = new ArrayList<>();
        for (JsonNode stream : mapper.readTree(response.body()).path("data").path("result")) {
            JsonNode labels = stream.path("stream");
            for (JsonNode value : stream.path("values")) {
                lines.add(toLine(labels, value.get(0).asString(), value.get(1).asString()));
            }
        }
        lines.sort(Comparator.comparing(LogLine::timestamp).reversed());
        return lines.size() > limit ? lines.subList(0, limit) : lines;
    }

    private LogLine toLine(JsonNode labels, String nanos, String raw) {
        String ts = Instant.ofEpochSecond(0, Long.parseLong(nanos)).toString();
        try {
            JsonNode json = mapper.readTree(raw);
            return new LogLine(ts,
                    text(json, "log.level", labels.path("level").asString("")),
                    text(json, "log.logger", ""),
                    text(json, "message", raw),
                    text(json, "correlationId", ""),
                    text(json, "camel.routeId", text(json, "routeId", "")),
                    labels.path("instance").asString(""),
                    raw);
        } catch (Exception notJson) {
            return new LogLine(ts, labels.path("level").asString(""), "", raw, "", "", labels.path("instance").asString(""), raw);
        }
    }

    /** Reads a field that may be flat ("log.level") or nested ({"log":{"level":...}}). */
    private static String text(JsonNode json, String dotted, String fallback) {
        JsonNode flat = json.get(dotted);
        if (flat != null && !flat.isNull()) {
            return flat.asString();
        }
        JsonNode node = json;
        for (String part : dotted.split("\\.")) {
            node = node.path(part);
        }
        return node.isMissingNode() || node.isNull() ? fallback : node.asString();
    }
}
