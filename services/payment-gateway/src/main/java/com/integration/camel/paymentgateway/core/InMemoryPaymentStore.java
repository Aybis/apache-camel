package com.integration.camel.paymentgateway.core;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.VirtualAccount;

/** Development store. See {@link PaymentStore} for why production must replace it. */
@Component
public class InMemoryPaymentStore implements PaymentStore {

    private final Map<String, Transfer> transfers = new ConcurrentHashMap<>();
    private final Map<String, VirtualAccount> virtualAccounts = new ConcurrentHashMap<>();

    @Override
    public Optional<Transfer> insertIfAbsent(Transfer transfer) {
        return Optional.ofNullable(transfers.putIfAbsent(transfer.id(), transfer));
    }

    @Override
    public void update(Transfer transfer) {
        transfers.put(transfer.id(), transfer);
    }

    @Override
    public Optional<Transfer> findTransfer(String id) {
        return Optional.ofNullable(transfers.get(id));
    }

    @Override
    public List<Transfer> findOpenTransfers() {
        return transfers.values().stream().filter(t -> !t.status().isFinal()).toList();
    }

    @Override
    public Optional<VirtualAccount> insertIfAbsent(VirtualAccount va) {
        return Optional.ofNullable(virtualAccounts.putIfAbsent(va.id(), va));
    }

    @Override
    public void update(VirtualAccount va) {
        virtualAccounts.put(va.id(), va);
    }

    @Override
    public Optional<VirtualAccount> findVirtualAccount(String id) {
        return Optional.ofNullable(virtualAccounts.get(id));
    }

    @Override
    public Optional<VirtualAccount> findVirtualAccountByNumber(String bank, String virtualAccountNo) {
        return virtualAccounts.values().stream()
                .filter(v -> bank.equals(v.request().bank()) && virtualAccountNo.equals(v.virtualAccountNo()))
                .findFirst();
    }
}
