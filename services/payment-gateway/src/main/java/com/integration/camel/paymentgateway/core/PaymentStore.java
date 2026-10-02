package com.integration.camel.paymentgateway.core;

import java.time.Duration;
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

    /**
     * Saves a new state of a transfer, only if the stored transfer is still open and has had exactly
     * {@code expectedStatusChecks} status checks (nobody else changed it meanwhile). Returns {@code false} when
     * another writer won; the caller then reloads the stored state and must not publish its own.
     */
    boolean update(Transfer transfer, int expectedStatusChecks);

    Optional<Transfer> findTransfer(String id);

    /**
     * Claims up to {@code limit} unresolved transfers (PENDING or UNKNOWN) that are due for a status check: last
     * changed at least {@code firstCheckAfter} ago, fewer than {@code maxChecks} checks, and not claimed by
     * anyone within the last {@code lease}. A claimed transfer is not returned to another caller (replica) until
     * its lease runs out, so each one is checked with the bank once per round.
     */
    List<Transfer> claimDueTransfers(Duration firstCheckAfter, int maxChecks, Duration lease, int limit);

    Optional<VirtualAccount> insertIfAbsent(VirtualAccount virtualAccount);

    /** Saves a new state of a virtual account; returns {@code false} (and changes nothing) if it is already paid. */
    boolean update(VirtualAccount virtualAccount);

    Optional<VirtualAccount> findVirtualAccount(String id);

    /**
     * Runs {@code work} with exclusive access to the virtual account with this number (empty if none), in one
     * transaction: {@link #update(VirtualAccount)} calls made inside commit or roll back together.
     */
    <T> T withLockedVirtualAccount(String bank, String virtualAccountNo, Function<Optional<VirtualAccount>, T> work);
}
