package com.integration.camel.paymentgateway.core;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.spi.BankAdapter;

/** All enabled bank adapters, by code. An adapter is enabled by {@code payment.banks.<code>.enabled=true}. */
@Component
public class BankAdapterRegistry {

    private final Map<String, BankAdapter> adapters = new TreeMap<>();

    public BankAdapterRegistry(List<BankAdapter> adapters) {
        for (BankAdapter a : adapters) {
            if (this.adapters.put(a.code(), a) != null) {
                throw new IllegalStateException("Two bank adapters use the code " + a.code());
            }
        }
    }

    public BankAdapter require(String code, BankAdapter.Capability capability) {
        BankAdapter a = code == null ? null : adapters.get(code.toLowerCase());
        if (a == null) {
            throw PaymentException.invalid("Unknown or disabled bank: " + code + ". Enabled: " + adapters.keySet());
        }
        if (capability != null && !a.capabilities().contains(capability)) {
            throw new PaymentException(422, "NOT_SUPPORTED", "Bank " + code + " does not support " + capability);
        }
        return a;
    }

    public Collection<BankAdapter> all() {
        return adapters.values();
    }
}
