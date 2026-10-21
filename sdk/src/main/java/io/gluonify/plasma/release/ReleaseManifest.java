package io.gluonify.plasma.release;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Signed release manifest (see gluonify-codex/docs/BOOTSTRAP.md). The exact bytes of the {@code manifest.json} file are signed (Ed25519, detached signature): nothing is
 * canonicalized, the signature is verified BEFORE reading the content. Artifacts are designated by a FILE NAME relative to the release directory (never by a URL:
 * a manifest cannot redirect to another host nor leave its directory).
 *
 * @param schema    format version (1)
 * @param version   distribution version (x.y.z)
 * @param sequence  number increasing from one publication to the next: a host refuses a sequence lower than the highest already seen (anti-rollback)
 * @param createdAt publication date (ISO-8601)
 * @param expires   validity deadline (ISO-8601): an old manifest replayed after this date is refused
 * @param artifacts one file per component and architecture
 */
public record ReleaseManifest(int schema, String version, long sequence, String createdAt, String expires, List<Artifact> artifacts) {
    /** @param name component; arch: amd64, arm64 or all; file: simple file name; sha256: lowercase hexadecimal digest; size: size in bytes. */
    public record Artifact(String name, String arch, String file, String sha256, long size) {
        /** Single file compressed with gzip ({@code .gz} name, excluding {@code .tar.gz}): sha256 and size are those of the served {@code .gz} (see {@link Gzip}). */
        public boolean gzipped() {
            return Gzip.isSingleFile(file);
        }

        /** Name of the file once decompressed (unchanged if it is not a plain {@code .gz}). */
        public String plainFile() {
            return Gzip.plainName(file);
        }
    }

    public static final int SCHEMA_VERSION = 1;
    /** @deprecated renamed {@link #SCHEMA_VERSION} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final int SCHEMA = SCHEMA_VERSION;
    /** Maximum number of artifacts in a manifest (defensive bound of the structural check; the file size is bounded separately). */
    public static final int MAX_ARTIFACTS = 1024;
    /** Maximum size of a manifest read (1024 artifacts of under 1 KiB per line, plus the header). */
    static final int MAX_MANIFEST_BYTES = 1024 * 1024 + 4096;
    public static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;
    public static final java.util.Set<String> NAMES = java.util.Set.of("gluonify-strange", "gluonify-higgs", "gluonify-bottom", "gluonify-gdown", "gluonify-node-image");
    /** Safe component name: lowercase letters, digits and hyphens (never a path nor a special character). */
    public static final Pattern SAFE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    public static final java.util.Set<String> ARCHS = java.util.Set.of("amd64", "arm64", "all");
    public static final Pattern VERSION_PATTERN = Pattern.compile("\\d{1,4}\\.\\d{1,4}\\.\\d{1,4}(-[\\dA-Za-z.]{1,16})?");
    /** @deprecated renamed {@link #VERSION_PATTERN} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final Pattern VERSION = VERSION_PATTERN;
    /** Simple file name: no separator, no "..", no leading dot. */
    public static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public static final class Invalid extends RuntimeException {
        public Invalid(final String message) {
            super(message);
        }
    }

    /** Structural check; raises {@link Invalid}. Verifies neither the signature nor the expiration (see {@link ReleaseVerifier}). */
    public ReleaseManifest validate() {
        return validate(NAMES::contains);
    }

    /**
     * Like {@link #validate()} with the component-name criterion chosen by the caller: a client (Strange, install.sh) keeps the closed list {@link #NAMES} (it only installs what
     * it knows); the store (sea) accepts any safe name ({@link #SAFE_NAME}), because it only stores and serves files already covered by the signature.
     */
    public ReleaseManifest validate(final java.util.function.Predicate<String> nameOk) {
        if (schema != SCHEMA_VERSION) throw new Invalid("unsupported manifest schema: " + schema);
        if (version == null || !VERSION_PATTERN.matcher(version).matches()) throw new Invalid("invalid version");
        if (sequence < 1) throw new Invalid("invalid sequence");
        requireDates();
        if (artifacts == null || artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS) throw new Invalid("invalid artifact list");
        var seen = new java.util.HashSet<String>();
        for (Artifact a : artifacts) {
            requireArtifact(a, nameOk);
            if (!seen.add(a.name() + "/" + a.arch())) throw new Invalid("artefact en double : " + a.name() + "/" + a.arch());
        }
        return this;
    }

    private void requireDates() {
        if (createdAt == null || expires == null) throw new Invalid("dates absentes");
        try {
            java.time.Instant.parse(createdAt);
            java.time.Instant.parse(expires);
        } catch (java.time.format.DateTimeParseException e) {
            throw new Invalid("invalid date");
        }
    }

    private static void requireArtifact(final Artifact a, final java.util.function.Predicate<String> nameOk) {
        if (a == null || a.name() == null || !nameOk.test(a.name())) throw new Invalid("unknown component: " + (a == null ? null : a.name()));
        if (a.arch() == null || !ARCHS.contains(a.arch())) throw new Invalid("invalid architecture: " + a.arch());
        if (a.file() == null || !FILE.matcher(a.file()).matches() || a.file().contains("..")) throw new Invalid("invalid file name: " + a.file());
        if (a.sha256() == null || !SHA.matcher(a.sha256()).matches()) throw new Invalid("invalid digest for " + a.file());
        if (a.size() < 1 || a.size() > MAX_ARTIFACT_BYTES) throw new Invalid("invalid size for " + a.file());
    }

    /**
     * The header, up to "\"artifacts\":[". The body (one line per artifact) and the footer ({@link #FOOT}) are read WITHOUT a regular expression: the repetition of a group
     * ({@code (?:[^\n]*\n)*}) is recursive in {@code java.util.regex} and overflowed the stack from ~5,000 lines (well below {@link #MAX_MANIFEST_BYTES}).
     */
    private static final Pattern HEAD = Pattern.compile("\\{\n\"schema\":(\\d+),\n\"version\":\"([^\"]*)\",\n\"sequence\":(\\d{1,15}),\n\"createdAt\":\"([^\"]*)\",\n\"expires\":\"([^\"]*)\",\n\"artifacts\":\\[\n");
    private static final String FOOT = "]\n}\n";
    private static final Pattern LINE = Pattern.compile("\\{\"name\":\"([^\"]*)\",\"arch\":\"([^\"]*)\",\"file\":\"([^\"]*)\",\"sha256\":\"([^\"]*)\",\"size\":(\\d{1,15})\\},?");

    /**
     * Reads the exact format produced by {@link #toJson()} (and bin/sign-release.sh); any other text is refused, and the result must re-serialize identically (no
     * ambiguity between what the publisher signs and what the node reads). To be called only after the signature is verified.
     */
    public static ReleaseManifest parse(final byte[] bytes) {
        return parse(bytes, NAMES::contains);
    }

    /** Like {@link #parse(byte[])} with the caller's component-name criterion (see {@link #validate(java.util.function.Predicate)}). */
    public static ReleaseManifest parse(final byte[] bytes, final java.util.function.Predicate<String> nameOk) {
        if (bytes.length > MAX_MANIFEST_BYTES) throw new Invalid("manifest too large"); // bounds the memory and the work of the parsing
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = HEAD.matcher(text);
        if (!m.lookingAt() || !text.endsWith(FOOT) || text.length() - FOOT.length() < m.end()) throw new Invalid("unreadable manifest");
        String body = text.substring(m.end(), text.length() - FOOT.length()); // complete lines (terminated by \n) or nothing
        if (!body.isEmpty() && body.charAt(body.length() - 1) != '\n') throw new Invalid("unreadable manifest");
        List<Artifact> list = new java.util.ArrayList<>();
        for (String line : body.isEmpty() ? new String[0] : body.split("\n")) {
            java.util.regex.Matcher a = LINE.matcher(line);
            if (!a.matches()) throw new Invalid("unreadable artifact line");
            list.add(new Artifact(a.group(1), a.group(2), a.group(3), a.group(4), Long.parseLong(a.group(5))));
        }
        ReleaseManifest r = new ReleaseManifest(Integer.parseInt(m.group(1)), m.group(2), Long.parseLong(m.group(3)), m.group(4), m.group(5), list);
        r.validate(nameOk);
        if (!r.toJson().equals(text)) throw new Invalid("manifeste non canonique");
        return r;
    }

    /** Artifact of a component for an architecture (or "all"). */
    public java.util.Optional<Artifact> find(final String name, final String arch) {
        return artifacts.stream().filter(a -> a.name().equals(name) && (a.arch().equals(arch) || a.arch().equals("all"))).findFirst();
    }

    /**
     * Text of the manifest, one artifact per line in the fixed key order: this is the format read by install.sh (sed), and produced by bin/sign-release.sh; the Java signer
     * ({@link ReleaseSigner}) produces the same bytes.
     */
    public String toJson() {
        var sb = new StringBuilder("{\n");
        sb.append("\"schema\":").append(schema).append(",\n");
        sb.append("\"version\":\"").append(version).append("\",\n");
        sb.append("\"sequence\":").append(sequence).append(",\n");
        sb.append("\"createdAt\":\"").append(createdAt).append("\",\n");
        sb.append("\"expires\":\"").append(expires).append("\",\n");
        sb.append("\"artifacts\":[\n");
        for (int i = 0; i < artifacts.size(); i++) {
            Artifact a = artifacts.get(i);
            sb.append("{\"name\":\"").append(a.name()).append("\",\"arch\":\"").append(a.arch()).append("\",\"file\":\"").append(a.file())
                    .append("\",\"sha256\":\"").append(a.sha256()).append("\",\"size\":").append(a.size()).append('}').append(i < artifacts.size() - 1 ? "," : "").append('\n');
        }
        return sb.append("]\n}\n").toString();
    }
}
