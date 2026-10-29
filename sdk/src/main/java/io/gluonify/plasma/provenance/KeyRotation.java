package io.gluonify.plasma.provenance;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Rotation of a provenance signing key (gluonify-codex/provenance.md, decision of the user 2026-10-29): "the key {@code oldKeyId} hands over to the key
 * {@code newKeyId} of the same kind; both are trusted for {@link #OVERLAP}, then the old one is retired".
 *
 * <p><b>Canonical bytes</b> (UTF-8, line ending {@code \n}, lines in this order):
 * <pre>
 * gluonify-key-rotation 1
 * kind bup
 * old bup-1a2b3c4d5e6f
 * new bup-9f8e7d6c5b4a
 * new-key &lt;Ed25519 SubjectPublicKeyInfo, Base64 DER&gt;
 * signed-at 2026-10-29T12:00:00Z
 * retire-old-after 2026-11-28T12:00:00Z
 * </pre>
 * <b>Token</b>: {@code base64url(bytes) "." base64url(signature by the OLD key) "." base64url(signature by the NEW key)}, without padding. The old key's signature is
 * the cross-signature (the trusted key vouches for its successor); the new key's one proves that its holder has the private part. The header separates this
 * signature domain from the provenance statements, release manifests and file claims signed by the same keys. Parsing is strict (canonical bytes only).
 *
 * @param kind      kind of both keys ({@code bup}, {@code release}, {@code local}); the verifier decides which kinds it lets rotate
 * @param oldKeyId  identifier of the key that hands over
 * @param newKeyId  identifier of the new key (different from the old one)
 * @param newKey    new public key, Base64 of the DER SubjectPublicKeyInfo
 * @param signedAt  signing instant, to the second, UTC
 * @author David CABILLIC
 */
public record KeyRotation(String kind, String oldKeyId, String newKeyId, String newKey, Instant signedAt) {

    /** First line of the canonical bytes. */
    public static final String MAGIC = "gluonify-key-rotation 1";

    /** Time during which both keys are trusted after the rotation (decision of the user, 2026-10-29). */
    public static final Duration OVERLAP = Duration.ofDays(30);

    /** Maximum size of the canonical bytes (a real rotation is under 300). */
    public static final int MAX_BYTES = 1024;

    private static final String ED25519 = "Ed25519";
    private static final String UNREADABLE = "key rotation: unreadable statement";
    private static final String UNREADABLE_TOKEN = "key rotation: unreadable token";
    private static final Pattern KIND = Pattern.compile("[a-z]{1,16}");
    private static final Pattern KEY_B64 = Pattern.compile("[A-Za-z0-9+/]{40,120}={0,2}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,1400}\\.[A-Za-z0-9_-]{86}\\.[A-Za-z0-9_-]{86}");
    private static final int LINES = 8;

    /**
     * Checks every field; the new key must be a readable Ed25519 public key.
     *
     * @param kind     kind of both keys
     * @param oldKeyId identifier of the old key
     * @param newKeyId identifier of the new key
     * @param newKey   new public key (Base64 DER)
     * @param signedAt signing instant
     * @throws IllegalArgumentException if a field is invalid
     */
    public KeyRotation {

        if (kind == null || !KIND.matcher(kind).matches()) throw new IllegalArgumentException("key rotation: invalid kind");
        if (oldKeyId == null || !ProvenanceStatement.KEY_ID.matcher(oldKeyId).matches()) throw new IllegalArgumentException("key rotation: invalid old key identifier");
        if (newKeyId == null || !ProvenanceStatement.KEY_ID.matcher(newKeyId).matches()) throw new IllegalArgumentException("key rotation: invalid new key identifier");
        if (oldKeyId.equals(newKeyId)) throw new IllegalArgumentException("key rotation: the new key must have another identifier");
        if (newKey == null || !KEY_B64.matcher(newKey).matches()) throw new IllegalArgumentException("key rotation: invalid new key");
        Provenance.publicKey(newKey);
        if (signedAt == null) throw new IllegalArgumentException("key rotation: signing date missing");
        signedAt = signedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * Instant from which the old key is no longer trusted: {@link #signedAt} plus {@link #OVERLAP}.
     *
     * @return the end of the overlap
     */
    public Instant retireOldAfter() {

        return signedAt.plus(OVERLAP);
    }

    /**
     * New public key, decoded.
     *
     * @return the Ed25519 public key
     */
    public PublicKey newPublicKey() {

        return Provenance.publicKey(newKey);
    }

    /**
     * Signed bytes.
     *
     * @return the canonical bytes
     */
    public byte[] canonicalBytes() {

        String text = MAGIC + "\nkind " + kind + "\nold " + oldKeyId + "\nnew " + newKeyId + "\nnew-key " + newKey + "\nsigned-at " + signedAt
                + "\nretire-old-after " + retireOldAfter() + "\n";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Strict parsing: any unexpected line, order, spacing or end of overlap other than {@link #OVERLAP} is refused.
     *
     * @param bytes canonical bytes
     * @return the rotation read
     * @throws IllegalArgumentException if the bytes are not the canonical bytes of a valid rotation
     */
    public static KeyRotation parse(final byte[] bytes) {

        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException(UNREADABLE);
        String[] l = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (l.length != LINES || !l[0].equals(MAGIC) || !l[LINES - 1].isEmpty()) throw new IllegalArgumentException(UNREADABLE);
        Instant at;
        try {
            at = Instant.parse(value(l[5], "signed-at"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("key rotation: unreadable signing date");
        }
        KeyRotation r = new KeyRotation(value(l[1], "kind"), value(l[2], "old"), value(l[3], "new"), value(l[4], "new-key"), at);
        if (!Arrays.equals(r.canonicalBytes(), bytes)) throw new IllegalArgumentException("key rotation: non-canonical statement");
        return r;
    }

    private static String value(final String line, final String key) {

        if (!line.startsWith(key + " ")) throw new IllegalArgumentException("key rotation: line \"" + key + "\" expected");
        String v = line.substring(key.length() + 1);
        if (v.isEmpty() || v.contains(" ")) throw new IllegalArgumentException("key rotation: invalid value for \"" + key + "\"");
        return v;
    }

    /**
     * Signs the rotation with the old key (cross-signature) and the new one (proof of possession).
     *
     * @param rotation the rotation
     * @param oldKey   private part of the key that hands over
     * @param newKey   private part of the new key
     * @return the token
     * @throws IllegalArgumentException if {@code newKey} is not the private part of {@link #newKey}
     */
    public static String sign(final KeyRotation rotation, final PrivateKey oldKey, final PrivateKey newKey) {

        byte[] b = rotation.canonicalBytes();
        byte[] byNew = signature(b, newKey);
        if (!valid(b, byNew, rotation.newPublicKey())) throw new IllegalArgumentException("key rotation: the new private key does not match the announced public key");
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString(b) + "." + enc.encodeToString(signature(b, oldKey)) + "." + enc.encodeToString(byNew);
    }

    /**
     * Valid shape of a token, without verifying anything.
     *
     * @param token the token
     * @return true if it has three parts of the expected alphabet and sizes
     */
    public static boolean wellFormed(final String token) {

        return token != null && TOKEN.matcher(token).matches();
    }

    /**
     * Identifier of the old key claimed by the token, read WITHOUT verification (to choose the public key); never to be used for a decision.
     *
     * @param token the token
     * @return the claimed old key identifier, or null if the token is unreadable
     */
    public static String claimedOldKeyId(final String token) {

        try {
            return parse(split(token)[0]).oldKeyId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Verifies the cross-signature under the old key, then reads the rotation, then verifies the proof of possession under the announced new key.
     *
     * @param token  the token
     * @param oldKey public part of the key that hands over (a trusted key of the verifier)
     * @return the verified rotation
     * @throws IllegalArgumentException if the token is unreadable or either signature is wrong
     */
    public static KeyRotation verify(final String token, final PublicKey oldKey) {

        byte[][] p = split(token);
        if (!valid(p[0], p[1], oldKey)) throw new IllegalArgumentException("key rotation: invalid signature of the old key");
        KeyRotation r = parse(p[0]);
        if (!valid(p[0], p[2], r.newPublicKey())) throw new IllegalArgumentException("key rotation: invalid signature of the new key");
        return r;
    }

    private static byte[][] split(final String token) {

        if (!wellFormed(token)) throw new IllegalArgumentException(UNREADABLE_TOKEN);
        String[] parts = token.split("\\.", -1);
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[][] p = {dec.decode(parts[0]), dec.decode(parts[1]), dec.decode(parts[2])};
            if (p[0].length > MAX_BYTES || p[1].length != 64 || p[2].length != 64) throw new IllegalArgumentException(UNREADABLE_TOKEN);
            return p;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(UNREADABLE_TOKEN, e);
        }
    }

    private static byte[] signature(final byte[] bytes, final PrivateKey key) {

        try {
            Signature sig = Signature.getInstance(ED25519);
            sig.initSign(key);
            sig.update(bytes);
            return sig.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("key rotation: signing impossible (Ed25519 key expected)", e);
        }
    }

    private static boolean valid(final byte[] bytes, final byte[] signature, final PublicKey key) {

        try {
            Signature sig = Signature.getInstance(ED25519);
            sig.initVerify(key);
            sig.update(bytes);
            return sig.verify(signature);
        } catch (GeneralSecurityException e) {
            return false;
        }
    }
}
