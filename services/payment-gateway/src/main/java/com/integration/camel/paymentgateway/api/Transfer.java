package com.integration.camel.paymentgateway.api;

import java.time.Instant;

/**
 * State of one transfer as the gateway knows it. Immutable; the store replaces it on every change.
 *
 * @param id                 gateway id (= clientReferenceId)
 * @param type               INTRABANK or INTERBANK, derived from the beneficiary bank code
 * @param externalId         X-EXTERNAL-ID sent with the instruction; needed for the bank's status inquiry
 * @param bankReference      bank's own reference, once known
 * @param bankResponseCode   last raw response code from the bank (e.g. SNAP {@code 2001700})
 */
public record Transfer(
        String id,
        TransferRequest request,
        TransferType type,
        PaymentStatus status,
        String externalId,
        String bankReference,
        String bankResponseCode,
        String bankResponseMessage,
        Instant createdAt,
        Instant updatedAt,
        int statusChecks) {

    public Transfer withOutcome(BankOutcome outcome) {
        return new Transfer(id, request, type, outcome.status(), externalId,
                outcome.bankReference() != null ? outcome.bankReference() : bankReference,
                outcome.responseCode(), outcome.responseMessage(), createdAt, Instant.now(), statusChecks);
    }

    public Transfer withStatusCheck(BankOutcome outcome) {
        Transfer t = withOutcome(outcome);
        return new Transfer(t.id, t.request, t.type, t.status, t.externalId, t.bankReference, t.bankResponseCode,
                t.bankResponseMessage, t.createdAt, t.updatedAt, statusChecks + 1);
    }
}
