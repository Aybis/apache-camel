package com.integration.camel.paymentgateway.api;

public enum TransferType {
    /** Both accounts at the same bank (SNAP service 17). */
    INTRABANK,
    /** Beneficiary at another bank via the bank's online interbank switch (SNAP service 18). */
    INTERBANK
}
