package com.integration.camel.paymentgateway.api;

/**
 * What an adapter learned from one call to the bank, already mapped to the bank-neutral status.
 * The raw code and message are kept for support and reconciliation.
 *
 * @param transactionNotFound the bank's status inquiry says it has no record of the transaction; the core
 *                            turns this into FAILED only after a grace period
 */
public record BankOutcome(PaymentStatus status, String bankReference, String responseCode, String responseMessage,
                          boolean transactionNotFound) {

    public BankOutcome(PaymentStatus status, String bankReference, String responseCode, String responseMessage) {
        this(status, bankReference, responseCode, responseMessage, false);
    }

    public static BankOutcome unknown(String reason) {
        return new BankOutcome(PaymentStatus.UNKNOWN, null, null, reason);
    }

    public static BankOutcome notSent(String reason) {
        return new BankOutcome(PaymentStatus.FAILED, null, "NOT_SENT", reason);
    }
}
