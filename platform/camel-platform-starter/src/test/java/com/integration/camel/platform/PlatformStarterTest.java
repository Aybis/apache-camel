package com.integration.camel.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.apache.camel.CamelExecutionException;
import org.apache.camel.EndpointInject;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.annotation.DirtiesContext;

@CamelSpringBootTest
@DirtiesContext
@SpringBootTest(classes = PlatformStarterTest.TestApp.class, properties = {
        "spring.application.name=starter-test",
        "platform.console.enabled=false",
        "platform.error-handling.dead-letter-uri=mock:dead",
        "platform.error-handling.redelivery-delay=1",
        "platform.error-handling.maximum-redeliveries=2"
})
class PlatformStarterTest {

    @Autowired
    ProducerTemplate producer;

    @Autowired
    Environment environment;

    @EndpointInject("mock:ok")
    MockEndpoint ok;

    @EndpointInject("mock:dead")
    MockEndpoint dead;

    @Test
    void globalMonitoringConfigIsLoadedWithLowestPrecedence() {
        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs");
        assertThat(environment.getProperty("camel.main.use-mdc-logging")).isEqualTo("true");
        // The test property wins over the global default (3).
        assertThat(environment.getProperty("platform.error-handling.maximum-redeliveries")).isEqualTo("2");
    }

    @Test
    void generatesCorrelationIdWhenAbsentAndKeepsAnInboundOne() throws Exception {
        ok.reset();
        ok.expectedMessageCount(2);
        producer.sendBody("direct:ok", "a");
        producer.sendBodyAndHeader("direct:ok", "b", "X-Correlation-Id", "abc-123");
        ok.assertIsSatisfied();

        String generated = ok.getExchanges().get(0).getIn().getHeader("X-Correlation-Id", String.class);
        assertThat(generated).isNotBlank();
        assertThat(ok.getExchanges().get(1).getIn().getHeader("X-Correlation-Id")).isEqualTo("abc-123");
    }

    @Test
    void failedMessagesGoToTheDeadLetterChannelAfterRedelivery() throws Exception {
        dead.reset();
        dead.expectedMessageCount(1);
        producer.sendBody("direct:fail", "poison");
        dead.assertIsSatisfied();
        Exchange failed = dead.getExchanges().get(0);
        assertThat(failed.getProperty(Exchange.EXCEPTION_CAUGHT, Exception.class)).hasMessage("boom");
        // useOriginalMessage: the dead letter receives the message exactly as it arrived.
        assertThat(failed.getIn().getBody(String.class)).isEqualTo("poison");
    }

    @Test
    void transactedConfigurationLeavesErrorsToTheCaller() {
        assertThatThrownBy(() -> producer.sendBody("direct:tx", "poison"))
                .isInstanceOf(CamelExecutionException.class);
    }

    @SpringBootApplication
    static class TestApp {
        @Bean
        RouteBuilder testRoutes() {
            return new RouteBuilder() {
                @Override
                public void configure() {
                    from("direct:ok").routeId("ok").to("mock:ok");
                    from("direct:fail").routeId("fail").throwException(new IllegalStateException("boom"));
                    from("direct:tx").routeId("tx").routeConfigurationId(PlatformRouteConfiguration.TRANSACTED)
                            .throwException(new IllegalStateException("boom"));
                }
            };
        }
    }
}
