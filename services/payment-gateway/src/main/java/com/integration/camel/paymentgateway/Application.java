package com.integration.camel.paymentgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import com.integration.camel.paymentgateway.core.PaymentProperties;

@SpringBootApplication
@EnableConfigurationProperties(PaymentProperties.class)
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
