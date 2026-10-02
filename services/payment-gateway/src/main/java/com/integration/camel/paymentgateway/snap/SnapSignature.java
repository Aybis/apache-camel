package com.integration.camel.paymentgateway.snap;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SNAP BI (Standar Nasional Open API Pembayaran, Bank Indonesia) signature functions.
 * <ul>
 *   <li>Access token (asymmetric): {@code SHA256withRSA(privateKey, clientKey + "|" + X-TIMESTAMP)}.</li>
 *   <li>Transactions (symmetric): {@code HMAC-SHA512(clientSecret,
 *       METHOD + ":" + path + ":" + accessToken + ":" + lowerHex(SHA-256(minify(body))) + ":" + X-TIMESTAMP)}.</li>
 * </ul>
 * All signatures are Base64. These are the published SNAP rules; confirm against the bank's own test
 * vectors at onboarding, because a shared misreading would pass our tests and still fail at the bank.
 */
public final class SnapSignature {

    public static final ZoneId JAKARTA = ZoneId.of("Asia/Jakarta");
    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");

    private SnapSignature() {
    }

    /** X-TIMESTAMP value, e.g. {@code 2026-10-02T13:45:00+07:00}. */
    public static String timestamp() {
        return OffsetDateTime.now(JAKARTA).truncatedTo(ChronoUnit.SECONDS).format(TIMESTAMP);
    }

    public static OffsetDateTime parseTimestamp(String value) {
        return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    public static String signAccessToken(PrivateKey key, String clientKey, String timestamp) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update((clientKey + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign access token request", e);
        }
    }

    public static boolean verifyAccessToken(PublicKey key, String clientKey, String timestamp, String signature) {
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initVerify(key);
            s.update((clientKey + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signature));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static String signTransaction(String clientSecret, String method, String path, String accessToken,
                                         String body, String timestamp) {
        String stringToSign = method.toUpperCase() + ":" + path + ":" + accessToken + ":"
                + sha256Hex(minify(body)) + ":" + timestamp;
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            return Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot sign transaction", e);
        }
    }

    public static boolean verifyTransaction(String clientSecret, String method, String path, String accessToken,
                                            String body, String timestamp, String signature) {
        if (signature == null) {
            return false;
        }
        String expected = signTransaction(clientSecret, method, path, accessToken, body, timestamp);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Removes whitespace outside JSON strings, keeping field order and number formats exactly as sent
     * (re-serialising through a JSON library could reorder fields or turn "10000.00" into 10000.0).
     */
    public static String minify(String json) {
        if (json == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(json.length());
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                out.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                out.append(c);
            } else if (!Character.isWhitespace(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    public static String sha256Hex(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static PrivateKey privateKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pemBody(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid RSA private key (expected PKCS#8 PEM)", e);
        }
    }

    public static PublicKey publicKey(String pem) {
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(pemBody(pem)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid RSA public key (expected X.509 PEM)", e);
        }
    }

    private static byte[] pemBody(String pem) {
        String body = pem.replaceAll("-----(BEGIN|END) [A-Z ]+-----", "").replaceAll("\\s", "");
        return Base64.getDecoder().decode(body);
    }
}
