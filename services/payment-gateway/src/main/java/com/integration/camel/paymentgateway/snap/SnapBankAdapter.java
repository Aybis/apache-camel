package com.integration.camel.paymentgateway.snap;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.PublicKey;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.security.SecureRandom;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.integration.camel.paymentgateway.api.BankOutcome;
import com.integration.camel.paymentgateway.api.PaymentStatus;
import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.TransferRequest;
import com.integration.camel.paymentgateway.api.TransferType;
import com.integration.camel.paymentgateway.api.VaPaymentNotice;
import com.integration.camel.paymentgateway.api.VirtualAccount;
import com.integration.camel.paymentgateway.api.VirtualAccountRequest;
import com.integration.camel.paymentgateway.core.BankProperties;
import com.integration.camel.paymentgateway.spi.BankAdapter;
import com.integration.camel.paymentgateway.spi.InboundCall;
import com.integration.camel.paymentgateway.spi.InboundRequest;
import com.integration.camel.paymentgateway.spi.InboundResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Complete adapter for a bank that implements Bank Indonesia's SNAP BI standard. A concrete bank extends it,
 * passes its code and Bank Indonesia bank code, and overrides only what differs:
 * <ul>
 *   <li>paths: configuration ({@code payment.banks.<code>.paths}) or {@link #defaultPath(String)};</li>
 *   <li>request fields (e.g. bank-specific {@code additionalInfo}): {@link #customize(String, ObjectNode, Object)};</li>
 *   <li>response-code interpretation: {@link #mapTransferResponse(SnapResponse)} and siblings.</li>
 * </ul>
 * Services used: transfer intrabank (17), transfer interbank (18), transfer status (36), create VA (27),
 * VA payment notification received from the bank (25), access token B2B (73).
 */
public abstract class SnapBankAdapter implements BankAdapter {

    public static final String OP_ACCESS_TOKEN = "access-token";
    public static final String OP_TRANSFER_INTRABANK = "transfer-intrabank";
    public static final String OP_TRANSFER_INTERBANK = "transfer-interbank";
    public static final String OP_TRANSFER_STATUS = "transfer-status";
    public static final String OP_VA_CREATE = "va-create";
    public static final String OP_VA_PAYMENT = "va-payment";

    private static final Map<String, String> SNAP_PATHS = Map.of(
            OP_ACCESS_TOKEN, "/v1.0/access-token/b2b",
            OP_TRANSFER_INTRABANK, "/v1.0/transfer-intrabank",
            OP_TRANSFER_INTERBANK, "/v1.0/transfer-interbank",
            OP_TRANSFER_STATUS, "/v1.0/transfer/status",
            OP_VA_CREATE, "/v1.0/transfer-va/create-va",
            OP_VA_PAYMENT, "/v1.0/transfer-va/payment");

    private static final DateTimeFormatter SNAP_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final SecureRandom RANDOM = new SecureRandom();

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final String code;
    protected final String bankIdCode;
    protected final BankProperties props;
    protected final JsonMapper json;
    protected final SnapHttpClient client;

    private final PublicKey inboundPublicKey;
    private final Map<String, Instant> inboundTokens = new ConcurrentHashMap<>();

    protected SnapBankAdapter(String code, String defaultBankIdCode, BankProperties props, CamelContext camel,
                              ProducerTemplate producer, JsonMapper json) {
        this.code = code;
        this.props = props;
        this.json = json;
        this.bankIdCode = props.getBankIdCode() != null ? props.getBankIdCode() : defaultBankIdCode;
        this.client = new SnapHttpClient(code, props, camel, producer, json, path(OP_ACCESS_TOKEN));
        String pem = props.getInbound().getPublicKey();
        this.inboundPublicKey = pem == null || pem.isBlank() ? null : SnapSignature.publicKey(pem);
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String bankIdCode() {
        return bankIdCode;
    }

    @Override
    public Set<Capability> capabilities() {
        Set<Capability> caps = EnumSet.of(Capability.TRANSFER_INTRABANK, Capability.TRANSFER_INTERBANK,
                Capability.TRANSFER_STATUS);
        if (props.getVaPartnerServiceId() != null && !props.getVaPartnerServiceId().isBlank()) {
            caps.add(Capability.VIRTUAL_ACCOUNT);
        }
        return caps;
    }

    /** SNAP path for an operation: configuration first, then {@link #defaultPath(String)}. */
    protected final String path(String operation) {
        return props.getPaths().getOrDefault(operation, defaultPath(operation));
    }

    protected String defaultPath(String operation) {
        return SNAP_PATHS.get(operation);
    }

    /** Hook for bank-specific request fields. {@code source} is the Transfer or VirtualAccount. */
    protected void customize(String operation, ObjectNode body, Object source) {
    }

    // ------------------------------------------------------------------ transfers

    @Override
    public BankOutcome transfer(Transfer transfer) {
        TransferRequest r = transfer.request();
        boolean intra = transfer.type() == TransferType.INTRABANK;
        String op = intra ? OP_TRANSFER_INTRABANK : OP_TRANSFER_INTERBANK;
        ObjectNode body = json.createObjectNode();
        body.put("partnerReferenceNo", transfer.id());
        body.set("amount", amount(r.amount(), r.currency()));
        if (!intra) {
            body.put("beneficiaryAccountName", r.beneficiaryName());
        }
        body.put("beneficiaryAccountNo", r.beneficiaryAccount());
        if (!intra) {
            body.put("beneficiaryBankCode", r.beneficiaryBankCode());
        }
        body.put("remark", r.remark() == null ? "" : truncate(r.remark(), 50));
        body.put("sourceAccountNo", r.sourceAccount());
        body.put("transactionDate", snapDate(transfer.createdAt()));
        body.set("additionalInfo", json.createObjectNode());
        customize(op, body, transfer);
        try {
            return mapTransferResponse(client.post(path(op), body, transfer.externalId()));
        } catch (BankCallException e) {
            return fromCallFailure(e);
        }
    }

    /**
     * SNAP transfer response: 2xx with case 00 is final success; 202 is accepted and still processing;
     * 409 (duplicate X-EXTERNAL-ID or reference) means an earlier attempt reached the bank, so the outcome
     * is resolved by status inquiry; 5xx (including 504 timeout) may have been processed, so also UNKNOWN;
     * any other 4xx is a definite rejection.
     */
    protected BankOutcome mapTransferResponse(SnapResponse r) {
        PaymentStatus status;
        if (r.httpStatus() == 202) {
            status = PaymentStatus.PENDING;
        } else if (r.isSuccess()) {
            status = PaymentStatus.SUCCESS;
        } else if (r.httpStatus() == 409 || r.httpStatus() >= 500 || r.httpStatus() / 100 == 2) {
            status = PaymentStatus.UNKNOWN;
        } else {
            status = PaymentStatus.FAILED;
        }
        return new BankOutcome(status, r.text("referenceNo"), r.responseCode(), r.responseMessage());
    }

    @Override
    public BankOutcome transferStatus(Transfer transfer) {
        ObjectNode body = json.createObjectNode();
        body.put("originalPartnerReferenceNo", transfer.id());
        if (transfer.bankReference() != null) {
            body.put("originalReferenceNo", transfer.bankReference());
        }
        body.put("originalExternalId", transfer.externalId());
        body.put("serviceCode", transfer.type() == TransferType.INTRABANK ? "17" : "18");
        body.put("transactionDate", snapDate(transfer.createdAt()));
        body.set("additionalInfo", json.createObjectNode());
        customize(OP_TRANSFER_STATUS, body, transfer);
        try {
            return mapStatusResponse(client.post(path(OP_TRANSFER_STATUS), body, SnapHttpClient.newExternalId()));
        } catch (BankCallException e) {
            return BankOutcome.unknown("status inquiry failed: " + e.getMessage());
        }
    }

    /**
     * latestTransactionStatus: 00 success; 01 initiated, 02 paying, 03 pending; 04 refunded, 05 cancelled,
     * 06 failed; 07 not found. Anything else, or a failed inquiry, leaves the outcome UNKNOWN.
     */
    protected BankOutcome mapStatusResponse(SnapResponse r) {
        if (!r.isSuccess()) {
            return new BankOutcome(PaymentStatus.UNKNOWN, null, r.responseCode(),
                    "status inquiry not answered: " + r.responseMessage());
        }
        String latest = r.text("latestTransactionStatus");
        String desc = r.text("transactionStatusDesc");
        String ref = r.text("originalReferenceNo");
        String message = "latestTransactionStatus=" + latest + (desc != null ? " " + desc : "");
        return switch (latest == null ? "" : latest) {
            case "00" -> new BankOutcome(PaymentStatus.SUCCESS, ref, r.responseCode(), message);
            case "01", "02", "03" -> new BankOutcome(PaymentStatus.PENDING, ref, r.responseCode(), message);
            case "04", "05", "06" -> new BankOutcome(PaymentStatus.FAILED, ref, r.responseCode(), message);
            case "07" -> new BankOutcome(PaymentStatus.UNKNOWN, ref, r.responseCode(), message, true);
            default -> new BankOutcome(PaymentStatus.UNKNOWN, ref, r.responseCode(), message);
        };
    }

    // ------------------------------------------------------------------ virtual accounts

    @Override
    public VaCreation createVirtualAccount(VirtualAccount va) {
        VirtualAccountRequest r = va.request();
        String partnerServiceId = leftPad(props.getVaPartnerServiceId().trim(), 8);
        String vaNo = partnerServiceId + r.customerNo();
        ObjectNode body = json.createObjectNode();
        body.put("partnerServiceId", partnerServiceId);
        body.put("customerNo", r.customerNo());
        body.put("virtualAccountNo", vaNo);
        body.put("virtualAccountName", r.name());
        body.put("trxId", va.id());
        if (r.amount() != null) {
            body.set("totalAmount", amount(r.amount(), r.currency()));
        }
        body.put("virtualAccountTrxType", r.amount() != null ? "C" : "O");
        if (r.expiresAt() != null) {
            body.put("expiredDate", r.expiresAt().atZoneSameInstant(SnapSignature.JAKARTA).format(SNAP_DATE));
        }
        body.set("additionalInfo", json.createObjectNode());
        customize(OP_VA_CREATE, body, va);
        try {
            return new VaCreation(vaNo.trim(), mapVaCreateResponse(client.post(path(OP_VA_CREATE), body,
                    SnapHttpClient.newExternalId())));
        } catch (BankCallException e) {
            return new VaCreation(vaNo.trim(), fromCallFailure(e));
        }
    }

    /** 2xx/00 or 409 (already exists) means the account is open and waiting for payment. */
    protected BankOutcome mapVaCreateResponse(SnapResponse r) {
        PaymentStatus status;
        if (r.isSuccess() || r.httpStatus() == 409) {
            status = PaymentStatus.PENDING;
        } else if (r.httpStatus() >= 500 || r.httpStatus() / 100 == 2) {
            status = PaymentStatus.UNKNOWN;
        } else {
            status = PaymentStatus.FAILED;
        }
        return new BankOutcome(status, null, r.responseCode(), r.responseMessage());
    }

    // ------------------------------------------------------------------ calls from the bank

    @Override
    public InboundCall parseInbound(InboundRequest req) {
        if (req.path().endsWith(path(OP_ACCESS_TOKEN))) {
            return InboundCall.complete(issueInboundToken(req));
        }
        if (req.path().endsWith(path(OP_VA_PAYMENT))) {
            InboundResponse denied = authenticateTransaction(req, "25");
            if (denied != null) {
                return InboundCall.complete(denied);
            }
            JsonNode body;
            try {
                body = json.readTree(req.body());
            } catch (RuntimeException e) {
                return InboundCall.complete(error(400, "4002501", "Invalid Field Format [body]"));
            }
            JsonNode paid = body.path("paidAmount");
            if (body.path("virtualAccountNo").isMissingNode() || body.path("paymentRequestId").isMissingNode()
                    || paid.path("value").isMissingNode()) {
                return InboundCall.complete(error(400, "4002502",
                        "Invalid Mandatory Field [virtualAccountNo/paymentRequestId/paidAmount]"));
            }
            VaPaymentNotice notice = new VaPaymentNotice(code, body.path("virtualAccountNo").asString().trim(),
                    body.path("paymentRequestId").asString(), new BigDecimal(paid.path("value").asString()),
                    paid.path("currency").asString("IDR"), body.path("trxDateTime").asString(null));
            return InboundCall.vaPayment(notice, body);
        }
        return InboundCall.complete(error(404, "4040000", "Unknown endpoint"));
    }

    @Override
    public InboundResponse respondVaPayment(InboundCall call, VaPaymentDecision decision) {
        JsonNode req = (JsonNode) call.context();
        return switch (decision) {
            case ACCEPTED, DUPLICATE -> {
                ObjectNode data = json.createObjectNode();
                for (String f : new String[] {"partnerServiceId", "customerNo", "virtualAccountNo",
                        "virtualAccountName", "paymentRequestId"}) {
                    if (req.has(f)) {
                        data.set(f, req.get(f));
                    }
                }
                data.set("paidAmount", req.get("paidAmount"));
                data.put("paymentFlagStatus", "00");
                ObjectNode reason = data.putObject("paymentFlagReason");
                reason.put("english", "Success");
                reason.put("indonesia", "Sukses");
                ObjectNode res = json.createObjectNode();
                res.put("responseCode", "2002500");
                res.put("responseMessage", "Successful");
                res.set("virtualAccountData", data);
                yield new InboundResponse(200, json.writeValueAsString(res));
            }
            case UNKNOWN_ACCOUNT -> error(404, "4042512", "Invalid Bill/Virtual Account [Not Found]");
            case AMOUNT_MISMATCH -> error(404, "4042513", "Invalid Amount");
            case NOT_PAYABLE -> error(404, "4042514", "Paid Bill");
            // Nothing was recorded; SNAP timeout, so the bank sends the notification again.
            case TRY_LATER -> error(504, "5042500", "Timeout");
        };
    }

    /** Bank asks us for an access token: verify its RSA signature over clientKey|timestamp. */
    private InboundResponse issueInboundToken(InboundRequest req) {
        BankProperties.Inbound in = props.getInbound();
        String clientKey = req.header("X-CLIENT-KEY");
        String timestamp = req.header("X-TIMESTAMP");
        if (inboundPublicKey == null || in.getClientKey() == null) {
            return error(401, "4017300", "Unauthorized. [Inbound calls not configured]");
        }
        if (!in.getClientKey().equals(clientKey)) {
            return error(401, "4017300", "Unauthorized. [Unknown client]");
        }
        if (!timestampFresh(timestamp)) {
            return error(400, "4007301", "Invalid Field Format [X-TIMESTAMP]");
        }
        if (!SnapSignature.verifyAccessToken(inboundPublicKey, clientKey, timestamp, req.header("X-SIGNATURE"))) {
            return error(401, "4017300", "Unauthorized. [Signature]");
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Duration ttl = in.getTokenTtl();
        Instant now = Instant.now();
        inboundTokens.values().removeIf(exp -> exp.isBefore(now));
        inboundTokens.put(token, now.plus(ttl));
        ObjectNode res = json.createObjectNode();
        res.put("responseCode", "2007300");
        res.put("responseMessage", "Successful");
        res.put("accessToken", token);
        res.put("tokenType", "Bearer");
        res.put("expiresIn", String.valueOf(ttl.toSeconds()));
        return new InboundResponse(200, json.writeValueAsString(res));
    }

    /** Verifies bearer token, timestamp window and HMAC signature; returns null when the call may proceed. */
    private InboundResponse authenticateTransaction(InboundRequest req, String serviceCode) {
        String auth = req.header("Authorization");
        String token = auth != null && auth.startsWith("Bearer ") ? auth.substring(7).trim() : null;
        Instant expiry = token == null ? null : inboundTokens.get(token);
        if (expiry == null || expiry.isBefore(Instant.now())) {
            return error(401, "401" + serviceCode + "01", "Invalid Token (B2B)");
        }
        String timestamp = req.header("X-TIMESTAMP");
        if (!timestampFresh(timestamp)) {
            return error(400, "400" + serviceCode + "01", "Invalid Field Format [X-TIMESTAMP]");
        }
        String secret = props.getInbound().getClientSecret();
        if (secret == null || !SnapSignature.verifyTransaction(secret, req.method(), req.path(), token, req.body(),
                timestamp, req.header("X-SIGNATURE"))) {
            return error(401, "401" + serviceCode + "00", "Unauthorized. [Signature]");
        }
        return null;
    }

    private boolean timestampFresh(String timestamp) {
        try {
            OffsetDateTime ts = SnapSignature.parseTimestamp(timestamp);
            long skew = Math.abs(Duration.between(ts.toInstant(), Instant.now()).toSeconds());
            return skew <= props.getInbound().getMaxClockSkew().toSeconds();
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ helpers

    protected InboundResponse error(int http, String responseCode, String message) {
        ObjectNode res = json.createObjectNode();
        res.put("responseCode", responseCode);
        res.put("responseMessage", message);
        return new InboundResponse(http, json.writeValueAsString(res));
    }

    protected BankOutcome fromCallFailure(BankCallException e) {
        log.warn("Call to bank {} failed ({}): {}", code, e.kind(), e.getMessage());
        return e.kind() == BankCallException.Kind.NOT_SENT ? BankOutcome.notSent(e.getMessage())
                : BankOutcome.unknown(e.getMessage());
    }

    protected ObjectNode amount(BigDecimal value, String currency) {
        ObjectNode a = json.createObjectNode();
        a.put("value", value.setScale(2, RoundingMode.UNNECESSARY).toPlainString());
        a.put("currency", currency == null ? "IDR" : currency);
        return a;
    }

    protected static String snapDate(Instant instant) {
        return instant.atZone(SnapSignature.JAKARTA).truncatedTo(ChronoUnit.SECONDS).format(SNAP_DATE);
    }

    protected static String leftPad(String value, int width) {
        return value.length() >= width ? value : " ".repeat(width - value.length()) + value;
    }

    protected static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
