package io.gluonify.plasma.release;

import java.util.regex.Pattern;

/**
 * Announcement of a publication key change (see gluonify-codex/docs/BOOTSTRAP.md §11 ter). {@code rollover.json} file of a release, SIGNED by the current publication key
 * ({@code rollover.json.sig}) and countersigned by the NEW key ({@code rollover.json.sig2}: proof that the new key exists and that its holder agrees).
 * It is kept apart from {@code manifest.json} ON PURPOSE: the manifest format is strict (a node or an install.sh of version 0.1.0 rejects any added field), so the announcement
 * cannot appear in it without preventing old nodes from reading the transition release. It is bound to the manifest by its digest ({@code manifestSha256}).
 *
 * @param schema         format version (1)
 * @param version        version of the distribution that carries the announcement (that of the manifest)
 * @param sequence       manifest sequence (identical)
 * @param manifestSha256 SHA-256 digest of the bytes of this version's {@code manifest.json}
 * @param createdAt      announcement date (ISO-8601)
 * @param nextKey        new Ed25519 public key, DER SubjectPublicKeyInfo in Base64 on one line
 * @param nextKeySha256  SHA-256 digest of this DER (the one clients pass to {@code --key-sha256})
 * @param retireOldAfter from this date, a manifest signed ONLY by the old key is refused (end of the overlap)
 */
public record KeyRollover(int schema, String version, long sequence, String manifestSha256, String createdAt, String nextKey, String nextKeySha256, String retireOldAfter) {
    public static final int SCHEMA_VERSION = 1;
    /** @deprecated renamed {@link #SCHEMA_VERSION} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final int SCHEMA = SCHEMA_VERSION;
    /** Maximum overlap accepted between the announcement and the end of the old key. */
    public static final java.time.Duration MAX_OVERLAP = java.time.Duration.ofDays(400);
    public static final int MAX_BYTES = 4096;
    static final Pattern B64 = Pattern.compile("[A-Za-z0-9+/]{40,120}={0,2}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern FORMAT = Pattern.compile("""
            \\{
            "schema":(\\d+),
            "version":"([^"]*)",
            "sequence":(\\d{1,15}),
            "manifestSha256":"([^"]*)",
            "createdAt":"([^"]*)",
            "nextKey":"([^"]*)",
            "nextKeySha256":"([^"]*)",
            "retireOldAfter":"([^"]*)"
            \\}
            """);

    /** Structural check; the key must be a readable Ed25519 whose digest is the announced one. */
    public KeyRollover validate() {
        if (schema != SCHEMA_VERSION) throw new ReleaseManifest.Invalid("unsupported key announcement schema: " + schema);
        if (version == null || !ReleaseManifest.VERSION_PATTERN.matcher(version).matches()) throw new ReleaseManifest.Invalid("announcement: invalid version");
        if (sequence < 1) throw new ReleaseManifest.Invalid("announcement: invalid sequence");
        if (manifestSha256 == null || !SHA.matcher(manifestSha256).matches()) throw new ReleaseManifest.Invalid("announcement: invalid manifest digest");
        java.time.Instant created;
        java.time.Instant retire;
        try {
            created = java.time.Instant.parse(createdAt);
            retire = java.time.Instant.parse(retireOldAfter);
        } catch (RuntimeException e) {
            throw new ReleaseManifest.Invalid("announcement: invalid date");
        }
        if (!retire.isAfter(created) || retire.isAfter(created.plus(MAX_OVERLAP))) throw new ReleaseManifest.Invalid("announcement: end of overlap out of range");
        if (nextKey == null || !B64.matcher(nextKey).matches()) throw new ReleaseManifest.Invalid("announcement: invalid key");
        TrustedKeys.Key k = TrustedKeys.Key.ofDer(nextKey); // raises Invalid if it is not an Ed25519
        if (nextKeySha256 == null || !k.sha256().equals(nextKeySha256)) throw new ReleaseManifest.Invalid("announcement: the digest does not match the key");
        return this;
    }

    public java.time.Instant retireAt() {
        return java.time.Instant.parse(retireOldAfter);
    }

    public TrustedKeys.Key key() {
        return TrustedKeys.Key.ofDer(nextKey);
    }

    /** Reads the exact format of {@link #toJson()} (identical re-serialization required); to be called only AFTER the signatures are verified. */
    public static KeyRollover parse(final byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ReleaseManifest.Invalid("key announcement too large");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = FORMAT.matcher(text);
        if (!m.matches()) throw new ReleaseManifest.Invalid("unreadable key announcement");
        KeyRollover r = new KeyRollover(Integer.parseInt(m.group(1)), m.group(2), Long.parseLong(m.group(3)), m.group(4), m.group(5), m.group(6), m.group(7), m.group(8));
        r.validate();
        if (!r.toJson().equals(text)) throw new ReleaseManifest.Invalid("non-canonical key announcement");
        return r;
    }

    /** Same format as bin/sign-release.sh --next-key. */
    public String toJson() {
        return "{\n\"schema\":" + schema + ",\n\"version\":\"" + version + "\",\n\"sequence\":" + sequence + ",\n\"manifestSha256\":\"" + manifestSha256 + "\",\n\"createdAt\":\"" + createdAt
                + "\",\n\"nextKey\":\"" + nextKey + "\",\n\"nextKeySha256\":\"" + nextKeySha256 + "\",\n\"retireOldAfter\":\"" + retireOldAfter + "\"\n}\n";
    }
}
