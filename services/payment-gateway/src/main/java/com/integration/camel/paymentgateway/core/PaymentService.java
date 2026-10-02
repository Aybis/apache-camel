package com.integration.camel.paymentgateway.core;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.integration.camel.paymentgateway.api.BankOutcome;
import com.integration.camel.paymentgateway.api.PaymentStatus;
import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.TransferRequest;
import com.integration.camel.paymentgateway.api.TransferType;
import com.integration.camel.paymentgateway.api.VaPaymentNotice;
import com.integration.camel.paymentgateway.api.VirtualAccount;
import com.integration.camel.paymentgateway.api.VirtualAccountRequest;
import com.integration.camel.paymentgateway.spi.BankAdapter;
import com.integration.camel.paymentgateway.spi.BankAdapter.Capability;
import com.integration.camel.paymentgateway.spi.BankAdapter.VaPaymentDecision;
import com.integration.camel.paymentgateway.spi.InboundCall;
import com.integration.camel.paymentgateway.spi.InboundRequest;
import com.integration.camel.paymentgateway.spi.InboundResponse;
import com.integration.camel.platform.CorrelationIdProcessor;
import com.integration.camel.platform.MdcScope;

/**
 * Bank-neutral payment logic: validation, idempotency, persistence before sending, outcome handling,
 * reconciliation of unresolved transfers and payment events. Contains no bank-specific code.
 */
@Service
public class PaymentService {

    public static final String EVENTS_ENDPOINT = "direct:payment-events";
    private static final Logger LOG = LoggerFactory.getLogger(PaymentService.class);
    private static final Pattern REFERENCE = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern DIGITS = Pattern.compile("[0-9]{1,34}");

    private final BankAdapterRegistry adapters;
    private final PaymentStore store;
    private final PaymentProperties properties;
    private final ProducerTemplate producer;
    private final CorrelationIdProcessor correlation;

    public PaymentService(BankAdapterRegistry adapters, PaymentStore store, PaymentProperties properties,
                          ProducerTemplate producer, CorrelationIdProcessor correlation) {
        this.correlation = correlation;
        this.adapters = adapters;
        this.store = store;
        this.properties = properties;
        this.producer = producer;
    }

    public record Submitted<T>(T value, boolean created) {
    }

    // ------------------------------------------------------------------ transfers

    /**
     * Sends a transfer at most once per client reference. A repeated request with the same reference and
     * the same content returns the stored transfer without contacting the bank; different content is refused.
     */
    public Submitted<Transfer> submitTransfer(TransferRequest in) {
        TransferRequest r = normalise(in);
        BankAdapter bank = adapters.require(r.bank(), null);
        TransferType type = r.beneficiaryBankCode().equals(bank.bankIdCode()) ? TransferType.INTRABANK
                : TransferType.INTERBANK;
        bank = adapters.require(r.bank(), type == TransferType.INTRABANK ? Capability.TRANSFER_INTRABANK
                : Capability.TRANSFER_INTERBANK);
        if (type == TransferType.INTERBANK && blank(r.beneficiaryName())) {
            throw PaymentException.invalid("beneficiaryName is required for interbank transfers");
        }

        Instant now = Instant.now();
        // Stored as UNKNOWN before the call: if the process dies mid-call, reconciliation asks the bank.
        Transfer t = new Transfer(r.clientReferenceId(), r, type, PaymentStatus.UNKNOWN, newRequestId(), null,
                null, "sending", now, now, 0);
        var existing = store.insertIfAbsent(t);
        if (existing.isPresent()) {
            if (!existing.get().request().equals(r)) {
                throw new PaymentException(409, "DUPLICATE_REFERENCE",
                        "clientReferenceId " + r.clientReferenceId() + " was already used for a different transfer");
            }
            return new Submitted<>(existing.get(), false);
        }

        BankOutcome outcome;
        try {
            outcome = bank.transfer(t);
        } catch (RuntimeException e) {
            LOG.error("Adapter {} threw on transfer {}; outcome unknown", bank.code(), t.id(), e);
            outcome = BankOutcome.unknown("adapter error: " + e.getMessage());
        }
        Transfer done = t.withOutcome(outcome);
        if (!store.update(done, t.statusChecks())) {
            // Reconciliation resolved it while the call was in flight; the stored state wins and was published.
            LOG.warn("Transfer {} was resolved concurrently; keeping the stored outcome", t.id());
            return new Submitted<>(reload(t.id()), true);
        }
        publish("transfer", done.id(), done.status(), done);
        return new Submitted<>(done, true);
    }

    public Transfer getTransfer(String id, boolean refresh) {
        Transfer t = store.findTransfer(id).orElseThrow(() -> PaymentException.notFound("No transfer " + id));
        return refresh && !t.status().isFinal() ? checkStatus(t) : t;
    }

    /** Asks the bank about every unresolved transfer that is due. Called by the reconciliation route. */
    public int reconcile() {
        PaymentProperties.Reconciliation cfg = properties.getReconciliation();
        Instant due = Instant.now().minus(cfg.getFirstCheckAfter());
        int checked = 0;
        for (Transfer t : store.findOpenTransfers()) {
            if (t.updatedAt().isBefore(due) && t.statusChecks() < cfg.getMaxChecks()) {
                checkStatus(t);
                checked++;
            }
        }
        return checked;
    }

    private Transfer checkStatus(Transfer t) {
        BankAdapter bank = adapters.require(t.request().bank(), Capability.TRANSFER_STATUS);
        BankOutcome o;
        try {
            o = bank.transferStatus(t);
        } catch (RuntimeException e) {
            o = BankOutcome.unknown("status adapter error: " + e.getMessage());
        }
        Duration age = Duration.between(t.createdAt(), Instant.now());
        if (o.transactionNotFound() && age.compareTo(properties.getReconciliation().getNotFoundGrace()) > 0) {
            o = new BankOutcome(PaymentStatus.FAILED, o.bankReference(), o.responseCode(),
                    "bank has no record of the transfer after " + age.toMinutes() + " min: " + o.responseMessage());
        } else if (o.status() == PaymentStatus.UNKNOWN && t.status() == PaymentStatus.PENDING) {
            // An unanswered inquiry does not undo what the bank already told us.
            o = new BankOutcome(PaymentStatus.PENDING, o.bankReference(), o.responseCode(), o.responseMessage());
        }
        Transfer updated = t.withStatusCheck(o);
        if (!store.update(updated, t.statusChecks())) {
            // Another instance or request checked it meanwhile; it owns the event.
            return reload(t.id());
        }
        if (updated.status().isFinal()) {
            publish("transfer", updated.id(), updated.status(), updated);
        } else if (updated.statusChecks() >= properties.getReconciliation().getMaxChecks()) {
            LOG.error("Transfer {} still {} after {} status checks; needs manual follow-up with {}", updated.id(),
                    updated.status(), updated.statusChecks(), bank.code());
            publish("transfer.manual-review", updated.id(), updated.status(), updated);
        }
        return updated;
    }

    // ------------------------------------------------------------------ virtual accounts

    public Submitted<VirtualAccount> createVirtualAccount(VirtualAccountRequest in) {
        VirtualAccountRequest r = normalise(in);
        BankAdapter bank = adapters.require(r.bank(), Capability.VIRTUAL_ACCOUNT);
        Instant now = Instant.now();
        VirtualAccount va = new VirtualAccount(r.clientReferenceId(), r, null, PaymentStatus.UNKNOWN, null,
                "creating", null, null, null, now, now);
        var existing = store.insertIfAbsent(va);
        if (existing.isPresent()) {
            VirtualAccount e = existing.get();
            if (!e.request().equals(r)) {
                throw new PaymentException(409, "DUPLICATE_REFERENCE",
                        "clientReferenceId " + r.clientReferenceId() + " was already used for a different account");
            }
            // Creating a virtual account moves no money, so an unresolved creation may be retried.
            if (e.status() != PaymentStatus.UNKNOWN) {
                return new Submitted<>(e, false);
            }
            va = e;
        }
        BankAdapter.VaCreation c;
        try {
            c = bank.createVirtualAccount(va);
        } catch (RuntimeException ex) {
            c = new BankAdapter.VaCreation(null, BankOutcome.unknown("adapter error: " + ex.getMessage()));
        }
        VirtualAccount done = va.withCreation(c.virtualAccountNo(), c.outcome());
        try {
            if (!store.update(done)) {
                // Paid in the meantime: the stored state wins.
                return new Submitted<>(store.findVirtualAccount(done.id()).orElseThrow(), existing.isEmpty());
            }
        } catch (DuplicateKeyException e) {
            // The number belongs to another reference (unique per bank in the database).
            store.update(va.withCreation(null, new BankOutcome(PaymentStatus.FAILED, null, "DUPLICATE_NUMBER",
                    "virtual account number " + c.virtualAccountNo() + " is already used by another reference")));
            throw new PaymentException(409, "DUPLICATE_VIRTUAL_ACCOUNT",
                    "Virtual account number " + c.virtualAccountNo() + " is already used by another reference");
        }
        publish("virtual-account.created", done.id(), done.status(), done);
        return new Submitted<>(done, existing.isEmpty());
    }

    public VirtualAccount getVirtualAccount(String id) {
        return store.findVirtualAccount(id).orElseThrow(() -> PaymentException.notFound("No virtual account " + id));
    }

    // ------------------------------------------------------------------ calls from banks

    public InboundResponse handleInbound(String bankCode, InboundRequest request) {
        BankAdapter bank = adapters.require(bankCode, null);
        InboundCall call = bank.parseInbound(request);
        if (call.response() != null) {
            return call.response();
        }
        VaPaymentDecision decision = applyVaPayment(call.vaPayment());
        return bank.respondVaPayment(call, decision);
    }

    /**
     * Applies a verified payment notification once. The decision is taken while holding the account's row lock,
     * so two deliveries of the same notification cannot both credit it; the database's unique index on the
     * bank's payment id is the backstop. The event is published only after the credit is committed.
     */
    VaPaymentDecision applyVaPayment(VaPaymentNotice n) {
        record Result(VaPaymentDecision decision, VirtualAccount paid) {
        }
        if (!validAmount(n.paidAmount())) {
            // Not a valid rupiah amount (or too large to store): never credit it.
            LOG.warn("Payment {} for virtual account {} at {} has an invalid amount {}", n.paymentRequestId(),
                    n.virtualAccountNo(), n.bank(), n.paidAmount());
            return VaPaymentDecision.AMOUNT_MISMATCH;
        }
        Result result;
        try {
            result = store.withLockedVirtualAccount(n.bank(), n.virtualAccountNo(), found -> {
                if (found.isEmpty() || found.get().status() == PaymentStatus.FAILED) {
                    LOG.warn("Payment {} for unknown virtual account {} at {}", n.paymentRequestId(),
                            n.virtualAccountNo(), n.bank());
                    return new Result(VaPaymentDecision.UNKNOWN_ACCOUNT, null);
                }
                VirtualAccount va = found.get();
                if (va.status() == PaymentStatus.SUCCESS) {
                    return new Result(n.paymentRequestId().equals(va.paymentRequestId())
                            ? VaPaymentDecision.DUPLICATE : VaPaymentDecision.NOT_PAYABLE, null);
                }
                BigDecimal expected = va.request().amount();
                if (expected != null && expected.compareTo(n.paidAmount()) != 0) {
                    return new Result(VaPaymentDecision.AMOUNT_MISMATCH, null);
                }
                if (va.request().expiresAt() != null
                        && va.request().expiresAt().toInstant().isBefore(Instant.now())) {
                    return new Result(VaPaymentDecision.NOT_PAYABLE, null);
                }
                VirtualAccount paid = va.withPayment(n);
                if (!store.update(paid)) {
                    // Cannot happen under the row lock unless the account was paid; refuse rather than guess.
                    return new Result(VaPaymentDecision.NOT_PAYABLE, null);
                }
                return new Result(VaPaymentDecision.ACCEPTED, paid);
            });
        } catch (DuplicateKeyException e) {
            // The bank's payment id already credited a different virtual account.
            LOG.error("Payment {} from {} was already applied to another virtual account; refusing it for {}",
                    n.paymentRequestId(), n.bank(), n.virtualAccountNo());
            return VaPaymentDecision.NOT_PAYABLE;
        }
        if (result.paid() != null) {
            publish("virtual-account.paid", result.paid().id(), result.paid().status(), result.paid());
        }
        return result.decision();
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Sends a payment event to the events route, carrying the caller's correlation ID.
     */
    private void publish(String event, String id, PaymentStatus status, Object payload) {
        String correlationId = MDC.get(CorrelationIdProcessor.MDC_KEY);
        Map<String, Object> headers = new HashMap<>(Map.of("paymentEvent", event, "paymentId", id,
                "paymentStatus", status.name()));
        if (correlationId != null) {
            headers.put(correlation.getHeader(), correlationId);
        }
        MdcScope.preserving(() -> producer.sendBodyAndHeaders(EVENTS_ENDPOINT, payload, headers));
    }

    private static TransferRequest normalise(TransferRequest r) {
        if (r == null) {
            throw PaymentException.invalid("body is required");
        }
        requireReference(r.clientReferenceId());
        requireDigits("sourceAccount", r.sourceAccount());
        requireDigits("beneficiaryAccount", r.beneficiaryAccount());
        if (r.beneficiaryBankCode() == null || !r.beneficiaryBankCode().matches("[0-9]{3}")) {
            throw PaymentException.invalid("beneficiaryBankCode must be the 3-digit Bank Indonesia code");
        }
        BigDecimal amount = requireAmount(r.amount());
        return new TransferRequest(r.bank() == null ? null : r.bank().toLowerCase(), r.clientReferenceId(),
                r.sourceAccount(), r.beneficiaryBankCode(), r.beneficiaryAccount(), r.beneficiaryName(), amount,
                currency(r.currency()), r.remark());
    }

    private static VirtualAccountRequest normalise(VirtualAccountRequest r) {
        if (r == null) {
            throw PaymentException.invalid("body is required");
        }
        requireReference(r.clientReferenceId());
        if (r.customerNo() == null || !r.customerNo().matches("[0-9]{1,20}")) {
            throw PaymentException.invalid("customerNo must be 1-20 digits");
        }
        if (blank(r.name())) {
            throw PaymentException.invalid("name is required");
        }
        BigDecimal amount = r.amount() == null ? null : requireAmount(r.amount());
        return new VirtualAccountRequest(r.bank() == null ? null : r.bank().toLowerCase(), r.clientReferenceId(),
                r.customerNo(), r.name(), amount, currency(r.currency()), r.expiresAt());
    }

    private static void requireReference(String ref) {
        if (ref == null || !REFERENCE.matcher(ref).matches()) {
            throw PaymentException.invalid("clientReferenceId must be 1-64 characters of A-Z, a-z, 0-9, _ or -");
        }
    }

    private static void requireDigits(String field, String value) {
        if (value == null || !DIGITS.matcher(value).matches()) {
            throw PaymentException.invalid(field + " must be digits only");
        }
    }

    /** Amounts are stored as NUMERIC(19,2): at most 17 digits before the decimal point. */
    static final int MAX_INTEGER_DIGITS = 17;

    static boolean validAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return false;
        }
        BigDecimal a = amount.stripTrailingZeros();
        return a.scale() <= 2 && a.precision() - a.scale() <= MAX_INTEGER_DIGITS;
    }

    private Transfer reload(String id) {
        return store.findTransfer(id).orElseThrow();
    }

    private static BigDecimal requireAmount(BigDecimal amount) {
        if (amount == null) {
            throw PaymentException.invalid("amount is required");
        }
        if (!validAmount(amount)) {
            throw PaymentException.invalid("amount must be positive, with at most " + MAX_INTEGER_DIGITS
                    + " digits before the decimal point and at most 2 decimals");
        }
        return amount.setScale(2);
    }

    private static String currency(String c) {
        String value = c == null ? "IDR" : c.toUpperCase();
        if (!value.matches("[A-Z]{3}")) {
            throw PaymentException.invalid("currency must be an ISO 4217 code");
        }
        return value;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Numeric id sent as the bank's per-request id (SNAP X-EXTERNAL-ID: digits, unique per day). */
    static String newRequestId() {
        return System.currentTimeMillis() + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000));
    }
}
