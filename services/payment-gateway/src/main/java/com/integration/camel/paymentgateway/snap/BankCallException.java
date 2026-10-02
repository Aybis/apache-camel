package com.integration.camel.paymentgateway.snap;

/** A call to a bank that produced no HTTP response. */
public class BankCallException extends RuntimeException {

    /**
     * {@code NOT_SENT}: the request certainly did not reach the bank (connection refused, DNS, TLS handshake).
     * {@code OUTCOME_UNKNOWN}: it may have reached the bank (read timeout, connection reset).
     */
    public enum Kind { NOT_SENT, OUTCOME_UNKNOWN }

    private final Kind kind;

    public BankCallException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
