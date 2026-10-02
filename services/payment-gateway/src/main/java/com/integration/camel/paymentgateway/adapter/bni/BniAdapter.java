package com.integration.camel.paymentgateway.adapter.bni;

import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.integration.camel.paymentgateway.core.PaymentProperties;
import com.integration.camel.paymentgateway.snap.SnapBankAdapter;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Bank Negara Indonesia (BNI, Bank Indonesia code 009) over SNAP BI.
 * <p>
 * Everything standard comes from {@link SnapBankAdapter}. BNI-specific differences belong here and in
 * {@code payment.banks.bni} (paths, fixed additionalInfo values). Confirm at onboarding with BNI's API
 * portal (digitalservices.bni.co.id): exact paths, mandatory {@code additionalInfo} fields, whether virtual
 * accounts run on SNAP or on BNI eCollection, and BNI's signature test vectors.
 */
@Component
@ConditionalOnProperty(prefix = "payment.banks.bni", name = "enabled", havingValue = "true")
public class BniAdapter extends SnapBankAdapter {

    public static final String CODE = "bni";

    public BniAdapter(PaymentProperties properties, CamelContext camel, ProducerTemplate producer, JsonMapper json) {
        super(CODE, "009", properties.bank(CODE), camel, producer, json);
    }

    /**
     * Adds the fixed {@code additionalInfo} values configured under {@code payment.banks.bni.extra}
     * (keys prefixed {@code additional-info.}), e.g. a device or channel identifier BNI assigns.
     */
    @Override
    protected void customize(String operation, ObjectNode body, Object source) {
        ObjectNode info = (ObjectNode) body.get("additionalInfo");
        props.getExtra().forEach((key, value) -> {
            if (key.startsWith("additional-info.")) {
                info.put(key.substring("additional-info.".length()), value);
            }
        });
    }
}
