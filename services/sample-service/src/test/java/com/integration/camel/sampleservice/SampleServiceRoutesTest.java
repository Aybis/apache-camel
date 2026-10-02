package com.integration.camel.sampleservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.camel.CamelContext;
import org.apache.camel.test.spring.junit6.CamelSpringBootTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@CamelSpringBootTest
@SpringBootTest(properties = {"platform.console.enabled=false", "server.port=0"})
class SampleServiceRoutesTest {

    @Autowired
    CamelContext camelContext;

    @Test
    void routesStart() {
        assertThat(camelContext.getRoute("sample-service-ping")).isNotNull();
    }
}
