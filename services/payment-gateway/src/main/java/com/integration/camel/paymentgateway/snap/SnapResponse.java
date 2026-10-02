package com.integration.camel.paymentgateway.snap;

import tools.jackson.databind.JsonNode;

/**
 * A bank's answer to a SNAP call.
 *
 * @param responseCode 7 characters: HTTP status (3) + service code (2) + case code (2), e.g. 2001700
 * @param externalId   the X-EXTERNAL-ID actually used (it changes if the call had to be re-authorised)
 */
public record SnapResponse(int httpStatus, String responseCode, String responseMessage, JsonNode body,
                           String externalId) {

    /** Case code, the last two digits of the response code ("00" = success). */
    public String caseCode() {
        return responseCode != null && responseCode.length() == 7 ? responseCode.substring(5) : "";
    }

    public boolean isSuccess() {
        return httpStatus / 100 == 2 && "00".equals(caseCode());
    }

    public String text(String field) {
        JsonNode n = body == null ? null : body.get(field);
        return n == null || n.isNull() ? null : n.asString();
    }
}
