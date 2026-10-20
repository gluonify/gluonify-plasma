package io.gluonify.plasma.provenance;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Provenance token: {@code base64url(canonical bytes of the statement) "." base64url(64-byte Ed25519 signature)}, without padding. A single string, carried
 * as is by {@code AppSpec.provenance} (Raft state, version history, rollback), {@code DeployRequest.provenance}, the stack configuration or a
 * {@code .sig} file. Signing and verification by the JDK ({@code Ed25519}), without dependencies.
 */
public final class Provenance {
    private Provenance() {}

    /** Maximum length of a token (statement of at most 2048 bytes, signature of 64). */
    public static final int MAX_TOKEN = 2840;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,2800}\\.[A-Za-z0-9_-]{86}");
    private static final Pattern ARTIFACT_SHA = Pattern.compile("#sha256=([0-9a-fA-F]{64})$");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("@(sha256:[0-9a-f]{64})$");

    /** Decoded token: statement (read only after verification, see {@link #verify}), signed bytes and signature. */
    public record Signed(ProvenanceStatement statement, byte[] statementBytes, byte[] signature) {
        @Override
        public boolean equals(final Object other) {
            return other instanceof Signed s && Objects.equals(statement, s.statement) && Arrays.equals(statementBytes, s.statementBytes)
                    && Arrays.equals(signature, s.signature);
        }

        @Override
        public int hashCode() {
            return Objects.hash(statement, Arrays.hashCode(statementBytes), Arrays.hashCode(signature));
        }

        @Override
        public String toString() {
            return "Signed[statement=" + statement + ", statementBytes=" + Arrays.toString(statementBytes) + ", signature=" + Arrays.toString(signature) + "]";
        }
    }

    /** Valid shape of a token (without verifying the signature): this is what {@code AppSpec} checks. */
    public static boolean wellFormed(String token) {
        return token != null && token.length() <= MAX_TOKEN && TOKEN.matcher(token).matches();
    }

    /** Signs the statement with an Ed25519 private key and returns the token. */
    public static String sign(ProvenanceStatement s, PrivateKey key) {
        byte[] b = s.canonicalBytes();
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(b);
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return enc.encodeToString(b) + "." + enc.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("provenance: signing impossible (Ed25519 key expected)", e);
        }
    }

    /** Splits the token without verifying anything; the statement is NOT read here (it is read only after the signature). */
    static byte[][] split(String token) {
        if (!wellFormed(token)) throw new IllegalArgumentException("provenance: unreadable token");
        int dot = token.indexOf('.');
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] b = dec.decode(token.substring(0, dot));
            byte[] s = dec.decode(token.substring(dot + 1));
            if (s.length != 64 || b.length > ProvenanceStatement.MAX_BYTES) throw new IllegalArgumentException("provenance: unreadable token");
            return new byte[][] {b, s};
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provenance: unreadable token");
        }
    }

    /**
     * Key identifier claimed by the token, read WITHOUT verification (to choose the public key); never to be used for a decision. Null if unreadable.
     */
    public static String claimedKeyId(String token) {
        try {
            return ProvenanceStatement.parse(split(token)[0]).keyId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Verifies the signature with {@code key}, then reads the statement. {@link IllegalArgumentException} if the token is unreadable or the signature is wrong. */
    public static Signed verify(String token, PublicKey key) {
        byte[][] p = split(token);
        boolean ok;
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initVerify(key);
            sig.update(p[0]);
            ok = sig.verify(p[1]);
        } catch (GeneralSecurityException e) {
            ok = false;
        }
        if (!ok) throw new IllegalArgumentException("provenance: invalid signature");
        return new Signed(ProvenanceStatement.parse(p[0]), p[0], p[1]);
    }

    /**
     * Does the statement cover exactly the deliverables of the application {@code app}? Identical name; with executables: each architecture of the specification
     * has the same digest in the statement (the statement may list others); without executables: the image is designated by the same digest. Returns the reason for
     * the refusal, or null.
     */
    public static String mismatch(ProvenanceStatement s, String app, Map<String, String> artifacts, String image) {
        if (!s.app().equals(app)) return "the statement is for another application";
        if (artifacts != null && !artifacts.isEmpty()) {
            for (var e : artifacts.entrySet()) {
                String sha = artifactSha256(e.getValue());
                if (sha == null) return "executable " + e.getKey() + " without #sha256= digest";
                if (!sha.equals(s.artifacts().get(e.getKey()))) return "digest of executable " + e.getKey() + " not covered by the statement";
            }
            return null;
        }
        String d = imageDigest(image);
        if (d == null) return "image without digest (@sha256:…)";
        if (!d.equals(s.imageDigest())) return "digest of the image not covered by the statement";
        return null;
    }

    /** SHA-256 (lowercase) of an executable reference {@code url#sha256=<hex>}, or null. */
    public static String artifactSha256(String ref) {
        if (ref == null) return null;
        Matcher m = ARTIFACT_SHA.matcher(ref);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** Digest {@code sha256:<hex>} of an image designated by {@code repository@sha256:<hex>}, or null (tag only). */
    public static String imageDigest(String image) {
        if (image == null) return null;
        Matcher m = IMAGE_DIGEST.matcher(image.trim());
        return m.find() ? m.group(1) : null;
    }

    /** True if both specifications run the same bytes: same architectures and same digests, or same image (the URLs may differ). */
    public static boolean sameDeliverable(Map<String, String> a1, String i1, Map<String, String> a2, String i2) {
        boolean bin1 = a1 != null && !a1.isEmpty(), bin2 = a2 != null && !a2.isEmpty();
        if (bin1 != bin2) return false;
        if (bin1) {
            if (!a1.keySet().equals(a2.keySet())) return false;
            for (String k : a1.keySet()) {
                String s1 = artifactSha256(a1.get(k));
                if (s1 == null || !s1.equals(artifactSha256(a2.get(k)))) return false;
            }
            return true;
        }
        return Objects.equals(i1 == null ? null : i1.trim(), i2 == null ? null : i2.trim());
    }

    // ------------------------------------------------------------------ keys

    /** Ed25519 public key: SubjectPublicKeyInfo in Base64 (DER) or PEM "PUBLIC KEY". */
    public static PublicKey publicKey(String text) {
        try {
            String b64 = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            PublicKey k = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
            return k;
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("provenance: unreadable Ed25519 public key");
        }
    }

    /** Ed25519 private key: PEM "PRIVATE KEY" (PKCS#8, as produced by {@code openssl genpkey -algorithm ed25519}). The content appears in no message. */
    public static PrivateKey privateKey(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("provenance: unreadable Ed25519 private key");
        }
    }

    /** Reads a private key from a file (PEM PKCS#8); only the path appears in the error. */
    public static PrivateKey readPrivateKey(Path file) throws IOException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        try {
            return privateKey(pem);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provenance: unreadable Ed25519 private key in " + file);
        }
    }

    /** Public key of an Ed25519 private key (Base64 of the DER SubjectPublicKeyInfo), to declare in {@code gluonify.higgs.provenance.trusted-keys.<id>}. */
    public static String publicKeyBase64(PublicKey k) {
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }
}
