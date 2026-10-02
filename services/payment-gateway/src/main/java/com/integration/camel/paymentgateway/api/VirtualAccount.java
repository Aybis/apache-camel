package com.integration.camel.paymentgateway.api;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * A virtual account and its collection state.
 *
 * @param status          PENDING until paid, SUCCESS once a valid payment notification arrived,
 *                        FAILED if the bank refused to create it
 * @param paymentRequestId bank's id of the payment; duplicate notifications with the same id are ignored
 */
public record VirtualAccount(
        String id,
        VirtualAccountRequest request,
        String virtualAccountNo,
        PaymentStatus status,
        String bankResponseCode,
        String bankResponseMessage,
        BigDecimal paidAmount,
        String paymentRequestId,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt) {

    public VirtualAccount withCreation(String vaNo, BankOutcome outcome) {
        return new VirtualAccount(id, request, vaNo, outcome.status(), outcome.responseCode(),
                outcome.responseMessage(), null, null, null, createdAt, Instant.now());
    }

    public VirtualAccount withPayment(VaPaymentNotice notice) {
        return new VirtualAccount(id, request, virtualAccountNo, PaymentStatus.SUCCESS, bankResponseCode,
                bankResponseMessage, notice.paidAmount(), notice.paymentRequestId(), Instant.now(), createdAt,
                Instant.now());
    }
}
