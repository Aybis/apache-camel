#!/usr/bin/env bash
# Add a bank to the payment gateway: generates its adapter and its configuration block.
#
#   bash services/payment-gateway/new-bank.sh <code> --bi-code <3 digits> --name "<Bank name>" [--proprietary]
#
# Examples:
#   bash services/payment-gateway/new-bank.sh mandiri --bi-code 008 --name "Bank Mandiri"
#   bash services/payment-gateway/new-bank.sh legacybank --bi-code 999 --name "Legacy Bank" --proprietary
#
# Without --proprietary the bank is assumed to follow Bank Indonesia's SNAP BI standard: the adapter extends
# SnapBankAdapter and works as soon as credentials are configured; you only override what the bank does
# differently. With --proprietary you get a skeleton implementing BankAdapter directly.
# The gateway core, API and routes never change when a bank is added.
set -euo pipefail

DIR="$(cd "$(dirname "$0")" && pwd)"
CODE="${1:-}"
shift || true
BI_CODE=""
NAME=""
PROPRIETARY=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --bi-code) BI_CODE="$2"; shift 2 ;;
    --name) NAME="$2"; shift 2 ;;
    --proprietary) PROPRIETARY=true; shift ;;
    *) echo "Unknown option: $1" >&2; exit 2 ;;
  esac
done

if [[ ! "$CODE" =~ ^[a-z][a-z0-9]{1,19}$ ]]; then
  echo "Bank code must be lower-case letters/digits, 2-20 chars, e.g. mandiri" >&2; exit 2
fi
if [[ ! "$BI_CODE" =~ ^[0-9]{3}$ ]]; then
  echo "--bi-code must be the 3-digit Bank Indonesia bank code, e.g. 008" >&2; exit 2
fi
NAME="${NAME:-$CODE}"
NAME="${NAME//\"/\'}"
PKG_DIR="$DIR/src/main/java/com/integration/camel/paymentgateway/adapter/$CODE"
if [[ -e "$PKG_DIR" ]]; then
  echo "Adapter $CODE already exists at $PKG_DIR" >&2; exit 1
fi
if grep -qE "^    $CODE:" "$DIR/src/main/resources/application.yml"; then
  echo "payment.banks.$CODE already exists in application.yml" >&2; exit 1
fi
CLASS="$(echo "${CODE:0:1}" | tr a-z A-Z)${CODE:1}Adapter"
ENV="$(echo "$CODE" | tr a-z A-Z)"
mkdir -p "$PKG_DIR"

if [[ "$PROPRIETARY" == false ]]; then
cat > "$PKG_DIR/$CLASS.java" <<JAVA
package com.integration.camel.paymentgateway.adapter.$CODE;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.core.PaymentProperties;
import com.integration.camel.paymentgateway.snap.SnapBankAdapter;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * $NAME (Bank Indonesia code $BI_CODE) over SNAP BI. Standard behaviour comes from {@link SnapBankAdapter};
 * put only what this bank does differently here, and its paths under payment.banks.$CODE.paths.
 */
@Component
@ConditionalOnProperty(prefix = "payment.banks.$CODE", name = "enabled", havingValue = "true")
public class $CLASS extends SnapBankAdapter {

    public static final String CODE = "$CODE";

    public $CLASS(PaymentProperties properties, CamelContext camel, ProducerTemplate producer, JsonMapper json) {
        super(CODE, "$BI_CODE", properties.bank(CODE), camel, producer, json);
    }

    /** Bank-specific request fields (e.g. mandatory additionalInfo). Remove if the bank needs none. */
    @Override
    protected void customize(String operation, ObjectNode body, Object source) {
    }
}
JAVA
else
cat > "$PKG_DIR/$CLASS.java" <<JAVA
package com.integration.camel.paymentgateway.adapter.$CODE;

import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.api.BankOutcome;
import com.integration.camel.paymentgateway.api.Transfer;
import com.integration.camel.paymentgateway.api.VirtualAccount;
import com.integration.camel.paymentgateway.core.BankProperties;
import com.integration.camel.paymentgateway.core.PaymentProperties;
import com.integration.camel.paymentgateway.spi.BankAdapter;
import com.integration.camel.paymentgateway.spi.InboundCall;
import com.integration.camel.paymentgateway.spi.InboundRequest;
import com.integration.camel.paymentgateway.spi.InboundResponse;

/**
 * $NAME (Bank Indonesia code $BI_CODE), proprietary API. Implement each operation following the rules in
 * {@link BankAdapter}: never throw for a bank rejection, return UNKNOWN when the request may have reached the
 * bank without a definite answer, never retry a money-moving call. Declare in {@link #capabilities()} only
 * what is implemented; the gateway refuses the rest with 422.
 */
@Component
@ConditionalOnProperty(prefix = "payment.banks.$CODE", name = "enabled", havingValue = "true")
public class $CLASS implements BankAdapter {

    public static final String CODE = "$CODE";

    private final BankProperties props;

    public $CLASS(PaymentProperties properties) {
        this.props = properties.bank(CODE);
    }

    @Override
    public String code() {
        return CODE;
    }

    @Override
    public String bankIdCode() {
        return props.getBankIdCode() != null ? props.getBankIdCode() : "$BI_CODE";
    }

    @Override
    public Set<Capability> capabilities() {
        return EnumSet.noneOf(Capability.class); // add each capability once implemented
    }

    @Override
    public BankOutcome transfer(Transfer transfer) {
        return BankOutcome.notSent("$CODE transfer not implemented");
    }

    @Override
    public BankOutcome transferStatus(Transfer transfer) {
        return BankOutcome.unknown("$CODE status inquiry not implemented");
    }

    @Override
    public VaCreation createVirtualAccount(VirtualAccount virtualAccount) {
        return new VaCreation(null, BankOutcome.notSent("$CODE virtual accounts not implemented"));
    }

    @Override
    public InboundCall parseInbound(InboundRequest request) {
        return InboundCall.complete(new InboundResponse(404, "{\"error\":\"not implemented\"}"));
    }

    @Override
    public InboundResponse respondVaPayment(InboundCall call, VaPaymentDecision decision) {
        // TRY_LATER means nothing was recorded: answer with an error the bank retries, never with success.
        return new InboundResponse(500, "{\"error\":\"not implemented\"}");
    }
}
JAVA
fi

cat >> "$DIR/src/main/resources/application.yml" <<YAML
    $CODE:
      # $NAME. Added by new-bank.sh; fill in from the bank's onboarding pack.
      enabled: \${${ENV}_ENABLED:false}
      bank-id-code: "$BI_CODE"
      base-url: \${${ENV}_BASE_URL:}
      client-key: \${${ENV}_CLIENT_KEY:}
      client-secret: \${${ENV}_CLIENT_SECRET:}
      private-key: \${${ENV}_PRIVATE_KEY:}
      partner-id: \${${ENV}_PARTNER_ID:}
      channel-id: \${${ENV}_CHANNEL_ID:}
      connect-timeout: 5s
      response-timeout: 30s
      va-partner-service-id: \${${ENV}_VA_PARTNER_SERVICE_ID:}
      inbound:
        client-key: \${${ENV}_INBOUND_CLIENT_KEY:}
        client-secret: \${${ENV}_INBOUND_CLIENT_SECRET:}
        public-key: \${${ENV}_INBOUND_PUBLIC_KEY:}
      paths: {}
      extra: {}
YAML

echo "Added bank '$CODE' ($NAME, BI code $BI_CODE)"
echo "  adapter: ${PKG_DIR#$DIR/}/$CLASS.java"
echo "  config:  src/main/resources/application.yml (payment.banks.$CODE)"
echo "Next: set ${ENV}_ENABLED=true and the ${ENV}_* credentials, check the bank's paths and"
echo "additionalInfo against its documentation, then mvn -pl services/payment-gateway verify."
