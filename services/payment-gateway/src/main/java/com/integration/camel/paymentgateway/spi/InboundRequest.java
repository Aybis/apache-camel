package com.integration.camel.paymentgateway.spi;

import java.util.Map;
import java.util.TreeMap;

/**
 * A call made by a bank to the gateway, e.g. {@code POST /snap/bni/v1.0/transfer-va/payment}.
 *
 * @param path    path below {@code /snap/<bank>}, e.g. {@code /v1.0/transfer-va/payment}
 * @param headers request headers, case-insensitive
 */
public record InboundRequest(String method, String path, Map<String, String> headers, String body) {

    public InboundRequest {
        TreeMap<String, String> ci = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(headers);
        headers = ci;
    }

    public String header(String name) {
        return headers.get(name);
    }
}
