package io.gluonify.plasma.release;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Trust checks of a release: Ed25519 signature of the manifest, expiration, sequence (anti-rollback), SHA-256 digest of the files.
 * The public key is in PEM "PUBLIC KEY" format (SubjectPublicKeyInfo), the one produced by {@code openssl pkey -pubout}.
 */
public final class ReleaseVerifier {
    private ReleaseVerifier() {}

    public static PublicKey publicKey(final String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ReleaseManifest.Invalid("unreadable public key");
        }
    }

    /** Detached signature: 64 raw bytes, or their Base64 encoding (text .sig file). */
    static byte[] decodeSignature(final byte[] sig) {
        if (sig.length == 64) return sig;
        try {
            byte[] raw = Base64.getMimeDecoder().decode(new String(sig, java.nio.charset.StandardCharsets.US_ASCII).trim());
            if (raw.length == 64) return raw;
        } catch (IllegalArgumentException ignored) {
            // not Base64: reported as unreadable below
        }
        throw new ReleaseManifest.Invalid("unreadable signature");
    }

    public static boolean signatureValid(final byte[] manifest, final byte[] signature, final PublicKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(manifest);
            return s.verify(decodeSignature(signature));
        } catch (GeneralSecurityException e) {
            return false;
        }
    }

    /**
     * Verifies the signature, reads the manifest with {@code parser} then checks its structure, expiration and sequence. None of the content is used before the signature.
     *
     * @param minSequence highest sequence already seen (or required); the manifest must be at least at this level
     */
    public static ReleaseManifest verify(final byte[] manifest, final byte[] signature, final PublicKey key, final java.util.function.Function<byte[], ReleaseManifest> parser, final long minSequence, final Instant now) {
        return verify(manifest, signature, key, parser, minSequence, now, ReleaseManifest.NAMES::contains);
    }

    /** Like {@link #verify(byte[], byte[], PublicKey, java.util.function.Function, long, Instant)} with the caller's component-name criterion (see {@link ReleaseManifest#validate(java.util.function.Predicate)}). */
    public static ReleaseManifest verify(final byte[] manifest, final byte[] signature, final PublicKey key, final java.util.function.Function<byte[], ReleaseManifest> parser, final long minSequence, final Instant now, final java.util.function.Predicate<String> nameOk) {
        if (!signatureValid(manifest, signature, key)) throw new ReleaseManifest.Invalid("invalid manifest signature");
        ReleaseManifest m = parser.apply(manifest).validate(nameOk);
        if (now.isAfter(Instant.parse(m.expires()))) throw new ReleaseManifest.Invalid("manifest expired (" + m.expires() + ")");
        if (m.sequence() < minSequence) throw new ReleaseManifest.Invalid("rollback refused: sequence " + m.sequence() + " < " + minSequence);
        return m;
    }

    /** Hexadecimal SHA-256 of in-memory bytes. */
    public static String sha256(final byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(final Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The downloaded file has the declared size and digest (constant-time comparison). */
    public static void checkFile(final Path file, final ReleaseManifest.Artifact a) throws IOException {
        if (Files.size(file) != a.size()) throw new ReleaseManifest.Invalid("unexpected size for " + a.file());
        if (!MessageDigest.isEqual(sha256(file).getBytes(), a.sha256().getBytes())) throw new ReleaseManifest.Invalid("invalid SHA-256 digest for " + a.file());
    }
}
