package com.integration.camel.banksimulator;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The bank side of SNAP BI signatures. Written separately from the gateway's implementation on purpose:
 * the simulator is an independent reading of the standard, not a mirror of the code under test.
 */
final class SnapCrypto {

    private SnapCrypto() {
    }

    static boolean verifyTokenRequest(PublicKey partnerKey, String clientKey, String timestamp, String signature) {
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(partnerKey);
            verifier.update((clientKey + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(signature));
        } catch (Exception e) {
            return false;
        }
    }

    static String signTokenRequest(PrivateKey key, String clientKey, String timestamp) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key);
        signer.update((clientKey + "|" + timestamp).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    /** HMAC-SHA512 over METHOD:path:token:lowerhex(sha256(minified body)):timestamp. */
    static String hmac(String secret, String method, String path, String token, String body, String timestamp)
            throws Exception {
        String minified = body == null ? "" : minify(body);
        String bodyHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(minified.getBytes(StandardCharsets.UTF_8)));
        String data = String.join(":", method, path, token, bodyHash, timestamp);
        Mac mac = Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
        return Base64.getEncoder().encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String minify(String json) {
        StringBuilder sb = new StringBuilder();
        boolean quoted = false;
        boolean escape = false;
        for (char c : json.toCharArray()) {
            if (escape) {
                escape = false;
            } else if (quoted && c == '\\') {
                escape = true;
            } else if (c == '"') {
                quoted = !quoted;
            }
            if (quoted || !Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static PublicKey publicKey(String pem) throws Exception {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(decode(pem)));
    }

    static PrivateKey privateKey(String pem) throws Exception {
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(decode(pem)));
    }

    private static byte[] decode(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", "").trim());
    }
}
