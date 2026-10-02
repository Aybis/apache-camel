package com.integration.camel.paymentgateway.spi;

/** HTTP response the gateway sends back to a bank-initiated call. */
public record InboundResponse(int httpStatus, String body) {
}
