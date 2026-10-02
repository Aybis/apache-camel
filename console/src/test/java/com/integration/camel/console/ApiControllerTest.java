package com.integration.camel.console;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiControllerTest.Postgres.class)
class ApiControllerTest {

    /** Same major version as the local stack (deploy/docker-compose.yml); never H2. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        Path dir = Files.createTempDirectory("console-test");
        Path registryFile = dir.resolve("services.yml");
        Files.writeString(registryFile, """
                services:
                  - name: order-sync
                    domain: orders
                    port: 8101
                    description: "Syncs orders"
                """);
        registry.add("console.data-dir", () -> dir.resolve("data").toString());
        registry.add("console.registry", registryFile::toString);
        registry.add("console.write-token", () -> "secret");
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ConfigStore store;

    @Test
    void registryServicesAreListedAsNeverSeen() throws Exception {
        mvc.perform(get("/api/services/order-sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.domain").value("orders"))
                .andExpect(jsonPath("$.port").value(8101))
                .andExpect(jsonPath("$.status").value("UNKNOWN"));
    }

    @Test
    void configChangesNeedTheTokenAreValidatedVersionedAndAudited() throws Exception {
        String change = """
                {"logLevels":{"root":"debug","org.apache.camel":"WARN"},
                 "properties":{"platform.error-handling.maximum-redeliveries":"5"},
                 "changedBy":"Ama","comment":"investigate retries"}""";

        mvc.perform(put("/api/services/order-sync/config").contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isForbidden());

        mvc.perform(put("/api/services/order-sync/config").header("X-Console-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"logLevels\":{\"ROOT\":\"LOUD\"},\"changedBy\":\"Ama\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("Invalid level")));

        mvc.perform(put("/api/services/order-sync/config").header("X-Console-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"properties\":{\"camel.component.jms.connection-factory.queue-manager\":\"QM2\"},\"changedBy\":\"Ama\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("connection setting")));

        mvc.perform(put("/api/services/order-sync/config").header("X-Console-Token", "secret")
                        .contentType(MediaType.APPLICATION_JSON).content(change))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.logLevels.ROOT").value("DEBUG"));

        mvc.perform(get("/api/services/order-sync/config"))
                .andExpect(jsonPath("$.properties['platform.error-handling.maximum-redeliveries']").value("5"));

        mvc.perform(get("/api/audit").param("service", "order-sync"))
                .andExpect(jsonPath("$[0].changedBy").value("Ama"))
                .andExpect(jsonPath("$[0].comment").value("investigate retries"));
    }

    @Test
    void unknownServiceConfigIs404SoServicesFallBackToLocalSettings() throws Exception {
        mvc.perform(get("/api/services/not-registered/config")).andExpect(status().isNotFound());
    }

    @Test
    void heartbeatRegistersAndMarksServiceUp() throws Exception {
        mvc.perform(post("/api/services/new-service/heartbeat").contentType(MediaType.APPLICATION_JSON).content("""
                        {"instance":"pod-1","environment":"test","domain":"misc","version":"1.0","camelVersion":"4.22.1",
                         "status":"UP","appliedConfigVersion":0,"startedAtEpochMs":0,
                         "routes":[{"id":"r1","status":"Started","from":"timer://x"}]}"""))
                .andExpect(status().isAccepted());

        mvc.perform(get("/api/services/new-service"))
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.instances[0].heartbeat.routes[0].id").value("r1"));
    }

    @Test
    void logQlEscapesUserInput() {
        org.assertj.core.api.Assertions.assertThat(
                        LokiClient.logQl("order-sync", java.util.List.of("error", "bogus"), "a`\"b", null))
                .isEqualTo("{service=\"order-sync\", level=~\"ERROR\"} |= `a\"b`");
    }

    @Test
    void concurrentSavesGetConsecutiveVersionsAndOneAuditEntryEach() throws Exception {
        store.registerIfAbsent("concurrency-check", "test");
        int saves = 8;
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(saves)) {
            var futures = java.util.stream.IntStream.range(0, saves)
                    .mapToObj(i -> pool.submit(() -> store.update("concurrency-check",
                            new Model.ConfigChange(java.util.Map.of("ROOT", "INFO"), java.util.Map.of(), "t" + i, null))))
                    .toList();
            for (var f : futures) {
                f.get();
            }
        }
        org.assertj.core.api.Assertions.assertThat(store.find("concurrency-check").orElseThrow().config().version())
                .isEqualTo(saves);
        org.assertj.core.api.Assertions.assertThat(store.audit("concurrency-check", 100))
                .extracting(Model.AuditEntry::toVersion)
                .containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
    }
}
