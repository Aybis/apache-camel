package __PACKAGE__;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@CamelSpringBootTest
@SpringBootTest(properties = {"platform.console.enabled=false", "server.port=0"})
@Import(__CLASS__RoutesTest.Postgres.class)
class __CLASS__RoutesTest {

    /** Real PostgreSQL; the image comes from the root pom (postgres.image), same as the local stack. Never H2. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse(System.getProperty("postgres.image")));
        }
    }

    @Autowired
    CamelContext camelContext;

    @Autowired
    JdbcClient jdbc;

    @Test
    void routesStart() {
        assertThat(camelContext.getRoute("__SERVICE__-ping")).isNotNull();
    }

    @Autowired
    ProcessedMessages processedMessages;

    @Test
    void migrationsRan() {
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message").query(Long.class).single()).isNotNegative();
    }

    @Test
    void aMessageIdIsClaimedOnceAndPurgedAfterRetention() {
        assertThat(processedMessages.claim("msg-1")).isTrue();
        assertThat(processedMessages.claim("msg-1")).isFalse();
        jdbc.sql("UPDATE processed_message SET processed_at = now() - interval '400 days' WHERE message_id = 'msg-1'").update();
        assertThat(processedMessages.purge()).isEqualTo(1);
        assertThat(processedMessages.claim("msg-1")).isTrue();
    }

    @Test
    void databaseErrorsDoNotCarryRowValues() {
        String sql = "INSERT INTO processed_message (message_id) VALUES (?)";
        jdbc.sql(sql).param("acct-1234567890").update();
        // Platform default logServerErrorDetail=false: no "Key (message_id)=(...)" detail in the exception.
        assertThatThrownBy(() -> jdbc.sql(sql).param("acct-1234567890").update())
                .satisfies(e -> assertThat(String.valueOf(e) + e.getCause()).doesNotContain("1234567890"));
    }
}
