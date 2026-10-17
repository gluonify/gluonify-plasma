package io.gluonify.plasma.store;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Storage capability token: the control-plane signs it (HMAC-SHA256, key = the service token) for ONE operation on ONE object, with a maximum size and an expiry;
 * the storage node verifies it without asking anyone. A client never has the service token: it receives this after the control-plane has verified its identity and its
 * quota. Format: {@code cap.<base64url(op|sha|max|exp|ns)>.<base64url(hmac)>}.
 */
public final class StoreCapability {
    private StoreCapability() {}

    public static final String PREFIX = "cap.";

    /** op: "put" or "get"; max: bytes allowed for writing (0 for reading); exp: seconds since the epoch. */
    public record Cap(String op, String sha, long max, long exp, String ns) {}

    public static String sign(String key, Cap c) {
        String payload = c.op() + "|" + c.sha() + "|" + c.max() + "|" + c.exp() + "|" + c.ns();
        Base64.Encoder b = Base64.getUrlEncoder().withoutPadding();
        String p = b.encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return PREFIX + p + "." + b.encodeToString(mac(key, p));
    }

    /** The capability if the token is properly signed by {@code key} and has not expired, otherwise null. */
    public static Cap verify(String key, String token, long nowSeconds) {
        try {
            if (token == null || !token.startsWith(PREFIX)) return null;
            String[] parts = token.substring(PREFIX.length()).split("\\.");
            if (parts.length != 2) return null;
            Base64.Decoder d = Base64.getUrlDecoder();
            if (!MessageDigest.isEqual(mac(key, parts[0]), d.decode(parts[1]))) return null;
            String[] f = new String(d.decode(parts[0]), StandardCharsets.UTF_8).split("\\|", -1);
            if (f.length != 5) return null;
            Cap c = new Cap(f[0], f[1], Long.parseLong(f[2]), Long.parseLong(f[3]), f[4]);
            return c.exp() > nowSeconds && (c.op().equals("put") || c.op().equals("get")) ? c : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static byte[] mac(String key, String data) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return m.doFinal(data.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
