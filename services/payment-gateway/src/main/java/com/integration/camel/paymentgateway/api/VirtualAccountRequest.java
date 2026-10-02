package com.integration.camel.paymentgateway.api;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Bank-neutral request to open a virtual account for collecting one payment
 * ({@code POST /api/payments/v1/virtual-accounts}).
 *
 * @param bank              adapter code, e.g. {@code bni}
 * @param clientReferenceId caller's unique reference (invoice or order number); idempotency key
 * @param customerNo        number appended to the bank-assigned prefix; numeric
 * @param name              name shown to the payer
 * @param amount            exact amount expected (closed amount); {@code null} for an open amount
 * @param currency          ISO 4217, defaults to IDR
 * @param expiresAt         when the account stops accepting payment
 */
public record VirtualAccountRequest(
        String bank,
        String clientReferenceId,
        String customerNo,
        String name,
        BigDecimal amount,
        String currency,
        OffsetDateTime expiresAt) {
}
