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
 * Déclaration de provenance signée (gluonify-codex/provenance.md) : « la clé {@code keyId} atteste, à {@code signedAt}, que l'application {@code app} peut exécuter ces
 * exécutables (empreinte SHA-256 par architecture) et/ou cette image (empreinte de manifeste OCI) ».
 *
 * <p><b>Octets canoniques</b> (UTF-8, fin de ligne {@code \n}, lignes dans cet ordre, architectures triées) :
 * <pre>
 * gluonify-provenance 1
 * app shop
 * artifact amd64 &lt;sha256 hexadécimal, 64 minuscules&gt;
 * artifact arm64 &lt;…&gt;
 * image sha256:&lt;64 minuscules&gt;
 * key bup-2026
 * signed-at 2026-10-11T20:00:00Z
 * </pre>
 * Au moins une ligne {@code artifact} ou la ligne {@code image}. L'en-tête {@code gluonify-provenance 1} sépare ce domaine de signature des autres usages des mêmes clés
 * (manifeste de version en JSON, revendications {@code gluonify-file 1} de {@code GzipSignature}). La lecture ({@link #parse}) est stricte : les octets relus doivent être
 * exactement les octets canoniques de la déclaration obtenue.
 */
public record ProvenanceStatement(String app, Map<String, String> artifacts, String imageDigest, String keyId, Instant signedAt) {
    public static final String MAGIC = "gluonify-provenance 1";
    static final Pattern APP = Pattern.compile("[a-z0-9]([-a-z0-9]{0,40}[a-z0-9])?");
    static final Pattern ARCH = Pattern.compile("[a-z0-9]{1,16}(-[a-z0-9]{1,16})?");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");
    static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-f]{64}");
    /** Identifiant d'une clé de confiance (celui de {@code gluonify.higgs.provenance.trusted-keys.<id>}). */
    public static final Pattern KEY_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,62}");
    /** Taille maximale des octets canoniques (une déclaration réelle en fait moins de 500). */
    public static final int MAX_BYTES = 2048;

    public ProvenanceStatement {
        if (app == null || !APP.matcher(app).matches()) throw new IllegalArgumentException("provenance : nom d'application invalide");
        TreeMap<String, String> a = new TreeMap<>();
        if (artifacts != null) for (var e : artifacts.entrySet()) {
            if (e.getKey() == null || !ARCH.matcher(e.getKey()).matches()) throw new IllegalArgumentException("provenance : architecture invalide");
            if (e.getValue() == null || !SHA.matcher(e.getValue()).matches()) throw new IllegalArgumentException("provenance : empreinte SHA-256 invalide pour " + e.getKey());
            a.put(e.getKey(), e.getValue());
        }
        if (a.size() > 16) throw new IllegalArgumentException("provenance : trop d'architectures");
        artifacts = Collections.unmodifiableMap(a);
        if (imageDigest != null && !DIGEST.matcher(imageDigest).matches()) throw new IllegalArgumentException("provenance : empreinte d'image invalide (sha256:<64 hexa>)");
        if (artifacts.isEmpty() && imageDigest == null) throw new IllegalArgumentException("provenance : ni exécutable ni image");
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("provenance : identifiant de clé invalide");
        if (signedAt == null) throw new IllegalArgumentException("provenance : date de signature absente");
        signedAt = signedAt.truncatedTo(ChronoUnit.SECONDS);
    }

    /** Octets signés. */
    public byte[] canonicalBytes() {
        StringBuilder sb = new StringBuilder(MAGIC).append('\n').append("app ").append(app).append('\n');
        for (var e : artifacts.entrySet()) sb.append("artifact ").append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        if (imageDigest != null) sb.append("image ").append(imageDigest).append('\n');
        sb.append("key ").append(keyId).append('\n').append("signed-at ").append(signedAt).append('\n');
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Lecture stricte : toute ligne inattendue, tout ordre ou espace différent est refusé (les octets doivent être canoniques). */
    public static ProvenanceStatement parse(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("provenance : déclaration illisible");
        String[] l = new String(bytes, StandardCharsets.UTF_8).split("\n", -1);
        if (l.length < 6 || !l[0].equals(MAGIC) || !l[l.length - 1].isEmpty()) throw new IllegalArgumentException("provenance : déclaration illisible");
        int i = 1;
        String app = value(l[i++], "app");
        Map<String, String> arts = new TreeMap<>();
        while (i < l.length && l[i].startsWith("artifact ")) {
            String[] p = l[i++].split(" ", -1);
            if (p.length != 3 || arts.put(p[1], p[2]) != null) throw new IllegalArgumentException("provenance : ligne artifact illisible");
        }
        String image = null;
        if (i < l.length && l[i].startsWith("image ")) image = value(l[i++], "image");
        if (i + 3 != l.length) throw new IllegalArgumentException("provenance : déclaration illisible");
        String key = value(l[i++], "key");
        Instant at;
        try {
            at = Instant.parse(value(l[i], "signed-at"));
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("provenance : date de signature illisible");
        }
        ProvenanceStatement s = new ProvenanceStatement(app, arts, image, key, at);
        if (!java.util.Arrays.equals(s.canonicalBytes(), bytes)) throw new IllegalArgumentException("provenance : déclaration non canonique");
        return s;
    }

    private static String value(String line, String key) {
        if (!line.startsWith(key + " ")) throw new IllegalArgumentException("provenance : ligne « " + key + " » attendue");
        String v = line.substring(key.length() + 1);
        if (v.isEmpty() || v.contains(" ")) throw new IllegalArgumentException("provenance : valeur de « " + key + " » invalide");
        return v;
    }

    /**
     * Déclaration des livrables d'une application, tels qu'ils figurent dans sa spécification : SHA-256 lu dans {@code url#sha256=<hexa>} de chaque exécutable, empreinte
     * {@code @sha256:<hexa>} de l'image. Un livrable sans empreinte (exécutable sans {@code #sha256=}, image désignée par une étiquette) ne peut pas être signé.
     */
    public static ProvenanceStatement of(String app, Map<String, String> artifacts, String image, String keyId, Instant signedAt) {
        Map<String, String> shas = new TreeMap<>();
        if (artifacts != null) for (var e : artifacts.entrySet()) {
            String sha = Provenance.artifactSha256(e.getValue());
            if (sha == null) throw new IllegalArgumentException("provenance : exécutable " + e.getKey() + " sans empreinte #sha256=");
            shas.put(e.getKey(), sha);
        }
        String digest = null;
        if (image != null && !image.isBlank() && (artifacts == null || artifacts.isEmpty())) {
            digest = Provenance.imageDigest(image);
            if (digest == null) throw new IllegalArgumentException("provenance : image sans empreinte (@sha256:…) : une étiquette ne peut pas être signée");
        }
        return new ProvenanceStatement(app, shas, digest, keyId, signedAt);
    }
}
