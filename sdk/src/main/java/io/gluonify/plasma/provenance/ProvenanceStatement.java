package io.gluonify.plasma.provenance;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Signed provenance statement (gluonify-codex/provenance.md): "the key {@code keyId} attests, at {@code signedAt}, that the application {@code app} may run these
 * executables (SHA-256 digest per architecture) and/or this image (OCI manifest digest)".
 *
 * <p><b>Canonical bytes</b> (UTF-8, line ending {@code \n}, lines in this order, architectures sorted):
 * <pre>
 * gluonify-provenance 1
 * app shop
 * artifact amd64 &lt;hexadecimal sha256, 64 lowercase&gt;
 * artifact arm64 &lt;…&gt;
 * image sha256:&lt;64 lowercase&gt;
 * key bup-2026
 * signed-at 2026-10-11T20:00:00Z
 * </pre>
 * At least one {@code artifact} line or the {@code image} line. The {@code gluonify-provenance 1} header separates this signature domain from the other uses of the same keys
 * (release manifest in JSON, {@code gluonify-file 1} claims of {@code GzipSignature}). Parsing ({@link #parse}) is strict: the bytes read back must be
 * exactly the canonical bytes of the statement obtained.
 */
public record ProvenanceStatement(String app, Map<String, String> artifacts, String imageDigest, String keyId, Instant signedAt) {
    private static final String UNREADABLE_STATEMENT = "provenance: unreadable statement";
    public static final String MAGIC = "gluonify-provenance 1";
    static final Pattern APP_PATTERN = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    static final Pattern ARCH = Pattern.compile("[a-z0-9]{1,16}(-[a-z0-9]{1,16})?");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    /** Identifier of a trusted key (that of {@code gluonify.higgs.provenance.trusted-keys.<id>}). */
    public static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");
    /** Maximum size of the canonical bytes (a real statement is under 500). */
    public static final int MAX_BYTES = 2048;

    public ProvenanceStatement {
        if (app == null || !APP_PATTERN.matcher(app).matches()) throw new IllegalArgumentException("provenance: invalid application name");
        artifacts = checkedArtifacts(artifacts);
        if (imageDigest != null && !DIGEST.matcher(imageDigest).matches()) throw new IllegalArgumentException("provenance: invalid image digest (sha256:<64 hex>)");
        if (artifacts.isEmpty() && imageDigest == null) throw new IllegalArgumentException("provenance: neither executable nor image");
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("provenance: invalid key identifier");
        if (signedAt == null) throw new IllegalArgumentException("provenance: signing date missing");
        signedAt = signedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    private static Map<String, String> checkedArtifacts(final Map<String, String> given) {
        TreeMap<String, String> a = new TreeMap<>();
        if (given != null) for (var e : given.entrySet()) {
            if (e.getKey() == null || !ARCH.matcher(e.getKey()).matches()) throw new IllegalArgumentException("provenance: invalid architecture");
            if (e.getValue() == null || !SHA.matcher(e.getValue()).matches()) throw new IllegalArgumentException("provenance: invalid SHA-256 digest for " + e.getKey());
            a.put(e.getKey(), e.getValue());
        }
        if (a.size() > 16) throw new IllegalArgumentException("provenance: too many architectures");
        return Collections.unmodifiableMap(a);
    }

    /** Signed bytes. */
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n').append("app ").append(app).append('\n');
        for (var e : artifacts.entrySet()) sb.append("artifact ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        if (imageDigest != null) sb.append("image ").append(imageDigest).append('\n');
        sb.append("key ").append(keyId).append('\n').append("signed-at ").append(signedAt).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Strict parsing: any unexpected line, any different order or spacing is rejected (the bytes must be canonical). */
    public static ProvenanceStatement parse(final byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException(UNREADABLE_STATEMENT);
        String[] l = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (l.length < 6 || !l[0].equals(MAGIC) || !l[l.length - 1].isEmpty()) throw new IllegalArgumentException(UNREADABLE_STATEMENT);
        int i = 1;
        String app = value(l[i++], "app");
        Map<String, String> arts = new TreeMap<>();
        while (i < l.length && l[i].startsWith("artifact ")) {
            String[] p = l[i++].split(" ", -1);
            if (p.length != 3 || arts.put(p[1], p[2]) != null) throw new IllegalArgumentException("provenance: unreadable artifact line");
        }
        String image = null;
        if (i < l.length && l[i].startsWith("image ")) image = value(l[i++], "image");
        if (i + 3 != l.length) throw new IllegalArgumentException(UNREADABLE_STATEMENT);
        String key = value(l[i++], "key");
        Instant at;
        try {
            at = Instant.parse(value(l[i], "signed-at"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("provenance: unreadable signing date");
        }
        ProvenanceStatement s = new ProvenanceStatement(app, arts, image, key, at);
        if (!java.util.Arrays.equals(s.canonicalBytes(), bytes)) throw new IllegalArgumentException("provenance: non-canonical statement");
        return s;
    }

    private static String value(final String line, final String key) {
        if (!line.startsWith(key + " ")) throw new IllegalArgumentException("provenance: line \"" + key + "\" expected");
        String v = line.substring(key.length() + 1);
        if (v.isEmpty() || v.contains(" ")) throw new IllegalArgumentException("provenance: invalid value for \"" + key + "\"");
        return v;
    }

    /**
     * Statement of an application's deliverables, as they appear in its specification: SHA-256 read from {@code url#sha256=<hex>} of each executable, digest
     * {@code @sha256:<hex>} of the image. A deliverable without a digest (executable without {@code #sha256=}, image designated by a tag) cannot be signed.
     */
    public static ProvenanceStatement of(final String app, final Map<String, String> artifacts, final String image, final String keyId, final Instant signedAt) {
        Map<String, String> shas = new TreeMap<>();
        if (artifacts != null) for (var e : artifacts.entrySet()) {
            String sha = Provenance.artifactSha256(e.getValue());
            if (sha == null) throw new IllegalArgumentException("provenance: executable " + e.getKey() + " without #sha256= digest");
            shas.put(e.getKey(), sha);
        }
        String digest = null;
        if (image != null && !image.isBlank() && (artifacts == null || artifacts.isEmpty())) {
            digest = Provenance.imageDigest(image);
            if (digest == null) throw new IllegalArgumentException("provenance: image without digest (@sha256:…): a tag cannot be signed");
        }
        return new ProvenanceStatement(app, shas, digest, keyId, signedAt);
    }
}
