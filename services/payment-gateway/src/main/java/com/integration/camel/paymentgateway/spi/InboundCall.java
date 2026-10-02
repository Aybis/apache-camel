package com.integration.camel.paymentgateway.spi;

import com.integration.camel.paymentgateway.api.VaPaymentNotice;

/**
 * Result of {@link BankAdapter#parseInbound}. Exactly one of {@code response} (the call is complete) or
 * {@code vaPayment} (the core must decide, then call {@link BankAdapter#respondVaPayment}) is set.
 *
 * @param context adapter-private data needed to build the reply (e.g. the parsed request body)
 */
public record InboundCall(InboundResponse response, VaPaymentNotice vaPayment, Object context) {

    public static InboundCall complete(InboundResponse response) {
        return new InboundCall(response, null, null);
    }

    public static InboundCall vaPayment(VaPaymentNotice notice, Object context) {
        return new InboundCall(null, notice, context);
    }
}
