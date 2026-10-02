package com.integration.camel.paymentgateway.api;

import java.math.BigDecimal;

/**
 * Bank-neutral transfer instruction, as received on {@code POST /api/payments/v1/transfers}.
 *
 * @param bank                 adapter code, e.g. {@code bni}
 * @param clientReferenceId    caller's unique reference; also the idempotency key
 * @param sourceAccount        debited account at {@code bank}
 * @param beneficiaryBankCode  Bank Indonesia bank code (e.g. 009 for BNI); same as the bank's own code means intrabank
 * @param beneficiaryAccount   credited account
 * @param beneficiaryName      required for interbank transfers
 * @param amount               positive, at most 2 decimals
 * @param currency             ISO 4217, defaults to IDR
 * @param remark               free text sent to the bank (truncated by the adapter if too long)
 */
public record TransferRequest(
        String bank,
        String clientReferenceId,
        String sourceAccount,
        String beneficiaryBankCode,
        String beneficiaryAccount,
        String beneficiaryName,
        BigDecimal amount,
        String currency,
        String remark) {
}
