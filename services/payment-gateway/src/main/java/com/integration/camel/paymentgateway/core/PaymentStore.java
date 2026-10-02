package com.integration.camel.paymentgateway.core;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.VirtualAccount;

/**
 * Persistence of transfers and virtual accounts. Implementations must make these guarantees hold across every
 * replica of the service, not only within one JVM:
 * <ul>
 *   <li>{@code insertIfAbsent} is atomic and durable before it returns: it is what stops two requests with the
 *       same client reference from both reaching the bank, and what lets reconciliation find a transfer whose
 *       sender crashed mid-call.</li>
 *   <li>A final status (SUCCESS, FAILED) is never overwritten.</li>
 *   <li>{@code withLockedVirtualAccount} serialises everything done to one virtual account, so a payment
 *       notification is credited once even when the bank delivers it twice at the same moment.</li>
 * </ul>
 */
public interface PaymentStore {

    /** Stores the transfer unless one with the same id exists; returns the existing one if so. */
    Optional<Transfer> insertIfAbsent(Transfer transfer);

    /** Saves a new state of a transfer; ignored if the stored transfer is already final. */
    void update(Transfer transfer);

    Optional<Transfer> findTransfer(String id);

    /** Transfers whose outcome is not final (PENDING or UNKNOWN). */
    List<Transfer> findOpenTransfers();

    Optional<VirtualAccount> insertIfAbsent(VirtualAccount virtualAccount);

    /** Saves a new state of a virtual account; ignored if the stored account is already paid. */
    void update(VirtualAccount virtualAccount);

    Optional<VirtualAccount> findVirtualAccount(String id);

    /**
     * Runs {@code work} with exclusive access to the virtual account with this number (empty if none), in one
     * transaction: {@link #update(VirtualAccount)} calls made inside commit or roll back together.
     */
    <T> T withLockedVirtualAccount(String bank, String virtualAccountNo, Function<Optional<VirtualAccount>, T> work);
}
