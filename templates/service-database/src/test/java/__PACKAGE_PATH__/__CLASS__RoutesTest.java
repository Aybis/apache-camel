package __PACKAGE__;

import static org.assertj.core.api.Assertions.assertThat;

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

    /** Real PostgreSQL, same major version as the local stack; never H2. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Postgres {
        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer(DockerImageName.parse("postgres:18.6-alpine"));
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

    @Test
    void migrationsRan() {
        assertThat(jdbc.sql("SELECT count(*) FROM processed_message").query(Long.class).single()).isZero();
    }
}
