package com.integration.camel.paymentgateway.api;

/**
 * Bank-neutral outcome of a payment instruction.
 * <ul>
 *   <li>{@code PENDING}: the bank accepted the instruction and has not finished it.</li>
 *   <li>{@code SUCCESS}, {@code FAILED}: final.</li>
 *   <li>{@code UNKNOWN}: the request may or may not have reached the bank (timeout, 5xx). The gateway
 *       never re-sends such an instruction; it resolves the outcome with a status inquiry.</li>
 * </ul>
 */
public enum PaymentStatus {
    PENDING, SUCCESS, FAILED, UNKNOWN;

    public boolean isFinal() {
        return this == SUCCESS || this == FAILED;
    }
}
