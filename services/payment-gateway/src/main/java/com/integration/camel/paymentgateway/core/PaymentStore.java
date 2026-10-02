package com.integration.camel.paymentgateway.core;

import java.util.List;
import java.util.Optional;

import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.VirtualAccount;

/**
 * Persistence of transfers and virtual accounts. {@code insertIfAbsent} must be atomic: it is what stops two
 * concurrent requests with the same client reference from both reaching the bank.
 * <p>
 * The only implementation today is in memory (single instance, lost on restart). Production needs a
 * database-backed implementation with a unique key on the id, before more than one replica runs.
 */
public interface PaymentStore {

    /** Stores the transfer unless one with the same id exists; returns the existing one if so. */
    Optional<Transfer> insertIfAbsent(Transfer transfer);

    void update(Transfer transfer);

    Optional<Transfer> findTransfer(String id);

    /** Transfers whose outcome is not final (PENDING or UNKNOWN). */
    List<Transfer> findOpenTransfers();

    Optional<VirtualAccount> insertIfAbsent(VirtualAccount virtualAccount);

    void update(VirtualAccount virtualAccount);

    Optional<VirtualAccount> findVirtualAccount(String id);

    Optional<VirtualAccount> findVirtualAccountByNumber(String bank, String virtualAccountNo);
}
