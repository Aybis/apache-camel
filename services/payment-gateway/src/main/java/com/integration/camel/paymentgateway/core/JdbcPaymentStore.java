package com.integration.camel.paymentgateway.core;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.integration.camel.paymentgateway.api.PaymentStatus;
import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.TransferRequest;
import com.integration.camel.paymentgateway.api.TransferType;
import com.integration.camel.paymentgateway.api.VirtualAccount;
import com.integration.camel.paymentgateway.api.VirtualAccountRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * PostgreSQL store (schema in {@code db/migration}). Each method outside {@code withLockedVirtualAccount} is its
 * own auto-committed statement, so a transfer inserted by {@link #insertIfAbsent(Transfer)} is durable before
 * the bank is called. Uniqueness and "final is final" are enforced by the database (primary keys, unique
 * indexes, and the status guard in every UPDATE), so they hold across replicas.
 */
@Component
public class JdbcPaymentStore implements PaymentStore {

    private static final String TRANSFER_COLUMNS = "id, request, type, status, external_id, bank_reference, "
            + "bank_response_code, bank_response_message, created_at, updated_at, status_checks";
    private static final String VA_COLUMNS = "id, request, virtual_account_no, status, bank_response_code, "
            + "bank_response_message, paid_amount, payment_request_id, paid_at, created_at, updated_at";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final JsonMapper json;

    public JdbcPaymentStore(JdbcTemplate jdbc, TransactionTemplate tx, JsonMapper json) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.json = json;
    }

    // ------------------------------------------------------------------ transfers

    @Override
    public Optional<Transfer> insertIfAbsent(Transfer t) {
        int inserted = jdbc.update("""
                INSERT INTO transfer (id, bank, request, amount, currency, type, status, external_id, bank_reference,
                    bank_response_code, bank_response_message, created_at, updated_at, status_checks)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""",
                t.id(), t.request().bank(), json.writeValueAsString(t.request()), t.request().amount(),
                t.request().currency(), t.type().name(), t.status().name(), t.externalId(), t.bankReference(),
                t.bankResponseCode(), truncate(t.bankResponseMessage()), ts(t.createdAt()), ts(t.updatedAt()),
                t.statusChecks());
        return inserted == 1 ? Optional.empty() : findTransfer(t.id());
    }

    @Override
    public boolean update(Transfer t, int expectedStatusChecks) {
        return jdbc.update("""
                UPDATE transfer SET status = ?, bank_reference = ?, bank_response_code = ?, bank_response_message = ?,
                    updated_at = ?, status_checks = ?
                WHERE id = ? AND status IN ('PENDING', 'UNKNOWN') AND status_checks = ?""",
                t.status().name(), t.bankReference(), t.bankResponseCode(), truncate(t.bankResponseMessage()),
                ts(t.updatedAt()), t.statusChecks(), t.id(), expectedStatusChecks) == 1;
    }

    @Override
    public Optional<Transfer> findTransfer(String id) {
        return jdbc.query("SELECT " + TRANSFER_COLUMNS + " FROM transfer WHERE id = ?", transferMapper(), id)
                .stream().findFirst();
    }

    /**
     * Claims due transfers in one statement: {@code FOR UPDATE SKIP LOCKED} keeps two replicas from claiming the
     * same row at the same moment, and the lease ({@code next_check_at}) keeps them from claiming it again until
     * the lease runs out. Times come from the database clock, so replica clock skew does not matter.
     */
    @Override
    public List<Transfer> claimDueTransfers(Duration firstCheckAfter, int maxChecks, Duration lease, int limit) {
        return jdbc.query("""
                WITH due AS (
                    SELECT id FROM transfer
                    WHERE status IN ('PENDING', 'UNKNOWN') AND status_checks < ?
                      AND updated_at <= now() - make_interval(secs => ?)
                      AND (next_check_at IS NULL OR next_check_at <= now())
                    ORDER BY updated_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED)
                UPDATE transfer t SET next_check_at = now() + make_interval(secs => ?)
                FROM due WHERE t.id = due.id
                RETURNING""" + " t." + TRANSFER_COLUMNS.replace(", ", ", t."),
                transferMapper(), maxChecks, seconds(firstCheckAfter), limit, seconds(lease));
    }

    private RowMapper<Transfer> transferMapper() {
        return (rs, n) -> new Transfer(rs.getString("id"),
                json.readValue(rs.getString("request"), TransferRequest.class),
                TransferType.valueOf(rs.getString("type")), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("external_id"), rs.getString("bank_reference"), rs.getString("bank_response_code"),
                rs.getString("bank_response_message"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getInt("status_checks"));
    }

    // ------------------------------------------------------------------ virtual accounts

    @Override
    public Optional<VirtualAccount> insertIfAbsent(VirtualAccount va) {
        int inserted = jdbc.update("""
                INSERT INTO virtual_account (id, bank, request, virtual_account_no, status, bank_response_code,
                    bank_response_message, created_at, updated_at)
                VALUES (?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING""",
                va.id(), va.request().bank(), json.writeValueAsString(va.request()), va.virtualAccountNo(),
                va.status().name(), va.bankResponseCode(), truncate(va.bankResponseMessage()), ts(va.createdAt()),
                ts(va.updatedAt()));
        return inserted == 1 ? Optional.empty() : findVirtualAccount(va.id());
    }

    @Override
    public boolean update(VirtualAccount va) {
        return jdbc.update("""
                UPDATE virtual_account SET virtual_account_no = ?, status = ?, bank_response_code = ?,
                    bank_response_message = ?, paid_amount = ?, payment_request_id = ?, paid_at = ?, updated_at = ?
                WHERE id = ? AND status <> 'SUCCESS'""",
                va.virtualAccountNo(), va.status().name(), va.bankResponseCode(), truncate(va.bankResponseMessage()),
                va.paidAmount(), va.paymentRequestId(), ts(va.paidAt()), ts(va.updatedAt()), va.id()) == 1;
    }

    @Override
    public Optional<VirtualAccount> findVirtualAccount(String id) {
        return jdbc.query("SELECT " + VA_COLUMNS + " FROM virtual_account WHERE id = ?", vaMapper(), id)
                .stream().findFirst();
    }

    @Override
    public <T> T withLockedVirtualAccount(String bank, String virtualAccountNo,
                                          Function<Optional<VirtualAccount>, T> work) {
        return tx.execute(status -> work.apply(jdbc.query("SELECT " + VA_COLUMNS
                        + " FROM virtual_account WHERE bank = ? AND virtual_account_no = ? FOR UPDATE",
                vaMapper(), bank, virtualAccountNo).stream().findFirst()));
    }

    private RowMapper<VirtualAccount> vaMapper() {
        return (rs, n) -> new VirtualAccount(rs.getString("id"),
                json.readValue(rs.getString("request"), VirtualAccountRequest.class),
                rs.getString("virtual_account_no"), PaymentStatus.valueOf(rs.getString("status")),
                rs.getString("bank_response_code"), rs.getString("bank_response_message"),
                rs.getBigDecimal("paid_amount"), rs.getString("payment_request_id"), instant(rs, "paid_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    // ------------------------------------------------------------------ helpers

    /** TIMESTAMPTZ is bound as {@code OffsetDateTime} (UTC), as pgJDBC documents for java.time. */
    private static OffsetDateTime ts(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return t == null ? null : t.toInstant();
    }

    private static double seconds(Duration d) {
        return d.toMillis() / 1000.0;
    }

    private static String truncate(String message) {
        return message == null || message.length() <= 500 ? message : message.substring(0, 500);
    }
}
