package com.integration.camel.paymentgateway.api;

import java.math.BigDecimal;

/** A verified payment notification for a virtual account, parsed by the bank's adapter. */
public record VaPaymentNotice(
        String bank,
        String virtualAccountNo,
        String paymentRequestId,
        BigDecimal paidAmount,
        String currency,
        String rawTransactionTime) {
}
