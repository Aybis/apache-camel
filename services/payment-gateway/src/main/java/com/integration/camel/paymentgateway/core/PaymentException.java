package com.integration.camel.paymentgateway.core;

/** A request the gateway refuses; carries the HTTP status and a stable error code for API callers. */
public class PaymentException extends RuntimeException {

    private final int httpStatus;
    private final String code;

    public PaymentException(int httpStatus, String code, String message) {
        super(message);
        this.httpStatus = httpStatus;
        this.code = code;
    }

    public static PaymentException invalid(String message) {
        return new PaymentException(400, "INVALID_REQUEST", message);
    }

    public static PaymentException notFound(String message) {
        return new PaymentException(404, "NOT_FOUND", message);
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String code() {
        return code;
    }
}
