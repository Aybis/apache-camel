package com.integration.camel.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.test.annotation.DirtiesContext;

/** The default dead letter channel (no dead-letter-uri configured) must never log bodies or headers. */
@CamelSpringBootTest
@DirtiesContext
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(classes = DeadLetterLogTest.TestApp.class, properties = {
        "spring.application.name=dead-letter-test",
        "platform.console.enabled=false",
        "platform.error-handling.redelivery-delay=1",
        "platform.error-handling.maximum-redeliveries=0"
})
class DeadLetterLogTest {

    @Autowired
    ProducerTemplate producer;

    @Autowired
    CamelContext camelContext;

    @Test
    void failuresAreLoggedWithoutBodyOrHeaders(CapturedOutput output) {
        producer.sendBodyAndHeader("direct:fails", "{\"accountNo\":\"1234567890\",\"name\":\"Budi Santoso\"}",
                "Authorization", "Bearer top-secret");

        assertThat(output.getOut()).contains("platform.dead-letter").contains("boom").contains("dead-letter-fails");
        assertThat(output.getOut()).doesNotContain("1234567890").doesNotContain("Budi Santoso")
                .doesNotContain("top-secret");
    }

    @Test
    void optInBodyLoggingUsesValidMaskedLogOptions() {
        PlatformProperties.ErrorHandling eh = new PlatformProperties.ErrorHandling();
        eh.setLogBody(true);
        String uri = PlatformRouteConfiguration.deadLetterUri(eh);
        assertThat(uri).contains("showBody=true").contains("logMask=true");
        assertThat(camelContext.getEndpoint(uri)).isNotNull();
        assertThat(camelContext.getRegistry().lookupByName("CamelCustomLogMask"))
                .isInstanceOf(PlatformMaskingFormatter.class);
    }

    @Test
    void anExplicitDeadLetterUriIsUsedAsIs() {
        PlatformProperties.ErrorHandling eh = new PlatformProperties.ErrorHandling();
        eh.setDeadLetterUri("jms:queue:ORDERS.BACKOUT");
        assertThat(PlatformRouteConfiguration.deadLetterUri(eh)).isEqualTo("jms:queue:ORDERS.BACKOUT");
    }

    @Test
    void maskingHidesAccountNumbersNamesAndSecrets() {
        String masked = new PlatformMaskingFormatter().format(
                "{\"accountNo\":\"1234567890\",\"name\":\"Budi Santoso\",\"amount\":\"150000.00\",\"password\":\"x\"}"
                        + " ref 0812345678901 code 2024");
        assertThat(masked).doesNotContain("1234567890").doesNotContain("Budi Santoso").doesNotContain("\"x\"")
                .doesNotContain("0812345678901").contains("******8901").contains("2024");
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        RouteBuilder failingRoute() {
            return new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:fails").routeId("dead-letter-fails")
                            .throwException(new IllegalStateException("boom"));
                }
            };
        }
    }
}
