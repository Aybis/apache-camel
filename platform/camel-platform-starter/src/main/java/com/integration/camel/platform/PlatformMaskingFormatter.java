package com.integration.camel.platform;

import java.util.Set;
import java.util.regex.Pattern;

import org.apache.camel.support.processor.DefaultMaskingFormatter;

/**
 * Masks personal and secret data wherever Camel log masking is on ({@code logMask=true}, e.g. the dead
 * letter log with {@code platform.error-handling.log-body=true}). Registered as the
 * {@code CamelCustomLogMask} bean. On top of Camel's keywords (password, secret, token...) it masks the
 * values of common account, name and contact fields (key=value, JSON, XML), and any run of 6 or more
 * digits outside them except its last 4 (account and card numbers, phone numbers).
 */
public class PlatformMaskingFormatter extends DefaultMaskingFormatter {

    static final Set<String> KEYWORDS = Set.of(
            "passphrase", "password", "secretKey", "accessKey", "secret", "token", "apiKey", "authorization",
            "accountNo", "accountNumber", "sourceAccountNo", "beneficiaryAccountNo", "virtualAccountNo",
            "accountName", "beneficiaryAccountName", "customerName", "name", "email", "phone", "phoneNo",
            "address", "cardNumber", "nik");

    private static final Pattern DIGITS = Pattern.compile("(?<![0-9])[0-9]{2,}([0-9]{4})(?![0-9])");

    public PlatformMaskingFormatter() {
        super(KEYWORDS, true, true, true);
    }

    @Override
    public String format(String source) {
        if (source == null) {
            return null;
        }
        String masked = super.format(source);
        return DIGITS.matcher(masked).replaceAll(m -> m.group().length() >= 6 ? "******" + m.group(1) : m.group());
    }
}
