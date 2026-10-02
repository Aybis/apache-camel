package com.integration.camel.paymentgateway.spi;

import java.util.Set;

import com.integration.camel.paymentgateway.api.BankOutcome;
import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.VaPaymentNotice;
import com.integration.camel.paymentgateway.api.VirtualAccount;

/**
 * The contract every bank implements. The gateway core (validation, idempotency, storage,
 * reconciliation, events, HTTP API) never contains bank-specific code; adding a bank means adding one
 * implementation of this interface plus its configuration under {@code payment.banks.<code>}.
 * <p>
 * Banks that follow Bank Indonesia's SNAP BI standard extend
 * {@link com.integration.camel.paymentgateway.snap.SnapBankAdapter}, which already implements signing,
 * token handling, the standard paths and response-code mapping; a subclass only overrides what the bank
 * does differently. Banks with a proprietary API implement this interface directly.
 * <p>
 * Rules for implementations:
 * <ul>
 *   <li>Never throw for a bank-side rejection; return a {@link BankOutcome} with {@code FAILED}.</li>
 *   <li>Return {@code UNKNOWN} whenever the request may have reached the bank but no definite answer came
 *       back (timeout, connection reset after sending, 5xx). Never retry a money-moving call yourself.</li>
 *   <li>Keep raw bank codes in the outcome so support can trace them.</li>
 * </ul>
 */
public interface BankAdapter {

    /** Short code used in the API and in configuration, e.g. {@code bni}. Lower case. */
    String code();

    /** Bank Indonesia bank code of this bank (e.g. {@code 009}); used to tell intrabank from interbank. */
    String bankIdCode();

    Set<Capability> capabilities();

    /** Sends a transfer instruction once. */
    BankOutcome transfer(Transfer transfer);

    /** Asks the bank for the current state of a transfer sent earlier. Safe to repeat. */
    BankOutcome transferStatus(Transfer transfer);

    /** Opens a virtual account. Returns the full virtual account number and the outcome. */
    VaCreation createVirtualAccount(VirtualAccount virtualAccount);

    /**
     * First step of a bank-initiated call (e.g. access token request, VA payment notification): authenticate
     * it and parse it. Calls that need no decision from the core (token requests, rejected signatures) carry
     * their final response.
     */
    InboundCall parseInbound(InboundRequest request);

    /** Second step for a VA payment notification: the bank-specific reply to the core's decision. */
    InboundResponse respondVaPayment(InboundCall call, VaPaymentDecision decision);

    enum Capability {
        TRANSFER_INTRABANK, TRANSFER_INTERBANK, TRANSFER_STATUS, VIRTUAL_ACCOUNT
    }

    record VaCreation(String virtualAccountNo, BankOutcome outcome) {
    }

    enum VaPaymentDecision {
        ACCEPTED, DUPLICATE, UNKNOWN_ACCOUNT, AMOUNT_MISMATCH, NOT_PAYABLE
    }
}
