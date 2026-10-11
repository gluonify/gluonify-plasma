package io.gluonify.plasma.release;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Manifeste de version signé (voir gluonify-codex/docs/BOOTSTRAP.md). Les octets exacts du fichier {@code manifest.json} sont signés (Ed25519, signature détachée) : rien n'est
 * canonicalisé, on vérifie la signature AVANT de lire le contenu. Les artefacts sont désignés par un NOM DE FICHIER relatif au dossier de la version (jamais par une URL :
 * un manifeste ne peut pas rediriger vers un autre hôte ni sortir de son dossier).
 *
 * @param schema    version du format (1)
 * @param version   version de la distribution (x.y.z)
 * @param sequence  numéro croissant d'une publication à l'autre : un hôte refuse une séquence inférieure à la plus haute déjà vue (anti-retour en arrière)
 * @param createdAt date de publication (ISO-8601)
 * @param expires   date limite de validité (ISO-8601) : un vieux manifeste rejoué après cette date est refusé
 * @param artifacts un fichier par composant et architecture
 */
public record ReleaseManifest(int schema, String version, long sequence, String createdAt, String expires, List<Artifact> artifacts) {
    /** @param name composant ; arch : amd64, arm64 ou all ; file : nom de fichier simple ; sha256 : empreinte hexadécimale minuscule ; size : taille en octets. */
    public record Artifact(String name, String arch, String file, String sha256, long size) {
        /** Fichier unique compressé en gzip (nom en {@code .gz}, hors {@code .tar.gz}) : sha256 et size sont ceux du {@code .gz} servi (voir {@link Gzip}). */
        public boolean gzipped() {
            return Gzip.isSingleFile(file);
        }

        /** Nom du fichier une fois décompressé (inchangé s'il n'est pas un {@code .gz} simple). */
        public String plainFile() {
            return Gzip.plainName(file);
        }
    }

    public static final int SCHEMA = 1;
    /** Nombre maximal d'artefacts d'un manifeste (borne défensive du contrôle structurel ; la taille du fichier est bornée à part). */
    public static final int MAX_ARTIFACTS = 1024;
    /** Taille maximale d'un manifeste lu (1024 artefacts de moins de 1 Kio par ligne, plus l'en-tête). */
    static final int MAX_MANIFEST_BYTES = 1024 * 1024 + 4096;
    public static final long MAX_ARTIFACT_BYTES = 512L * 1024 * 1024;
    public static final java.util.Set<String> NAMES = java.util.Set.of("gluonify-strange", "gluonify-higgs", "gluonify-bottom", "gluonify-gdown", "gluonify-node-image");
    /** Nom de composant sûr : minuscules, chiffres et tirets (jamais de chemin ni de caractère spécial). */
    public static final Pattern SAFE_NAME = Pattern.compile("[a-z][a-z0-9-]{1,63}");
    public static final java.util.Set<String> ARCHS = java.util.Set.of("amd64", "arm64", "all");
    public static final Pattern VERSION = Pattern.compile("[0-9]{1,4}\\.[0-9]{1,4}\\.[0-9]{1,4}(-[0-9A-Za-z.]{1,16})?");
    /** Nom de fichier simple : pas de séparateur, pas de « .. », pas de point initial. */
    public static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    public static final class Invalid extends RuntimeException {
        public Invalid(String message) {
            super(message);
        }
    }

    /** Contrôle structurel ; lève {@link Invalid}. Ne vérifie ni la signature ni l'expiration (voir {@link ReleaseVerifier}). */
    public ReleaseManifest validate() {
        return validate(NAMES::contains);
    }

    /**
     * Comme {@link #validate()} avec le critère des noms de composants choisi par l'appelant : un client (Strange, install.sh) garde la liste fermée {@link #NAMES} (il n'installe que ce
     * qu'il connaît) ; le réservoir (sea) accepte tout nom sûr ({@link #SAFE_NAME}), car il ne fait que stocker et servir des fichiers déjà couverts par la signature.
     */
    public ReleaseManifest validate(java.util.function.Predicate<String> nameOk) {
        if (schema != SCHEMA) throw new Invalid("schéma de manifeste non pris en charge : " + schema);
        if (version == null || !VERSION.matcher(version).matches()) throw new Invalid("version invalide");
        if (sequence < 1) throw new Invalid("séquence invalide");
        if (createdAt == null || expires == null) throw new Invalid("dates absentes");
        try {
            java.time.Instant.parse(createdAt);
            java.time.Instant.parse(expires);
        } catch (java.time.format.DateTimeParseException e) {
            throw new Invalid("date invalide");
        }
        if (artifacts == null || artifacts.isEmpty() || artifacts.size() > MAX_ARTIFACTS) throw new Invalid("liste d'artefacts invalide");
        var seen = new java.util.HashSet<String>();
        for (Artifact a : artifacts) {
            if (a == null || a.name() == null || !nameOk.test(a.name())) throw new Invalid("composant inconnu : " + (a == null ? null : a.name()));
            if (a.arch() == null || !ARCHS.contains(a.arch())) throw new Invalid("architecture invalide : " + a.arch());
            if (a.file() == null || !FILE.matcher(a.file()).matches() || a.file().contains("..")) throw new Invalid("nom de fichier invalide : " + a.file());
            if (a.sha256() == null || !SHA.matcher(a.sha256()).matches()) throw new Invalid("empreinte invalide pour " + a.file());
            if (a.size() < 1 || a.size() > MAX_ARTIFACT_BYTES) throw new Invalid("taille invalide pour " + a.file());
            if (!seen.add(a.name() + "/" + a.arch())) throw new Invalid("artefact en double : " + a.name() + "/" + a.arch());
        }
        return this;
    }

    /**
     * L'en-tête, jusqu'à « "artifacts":[ ». Le corps (une ligne par artefact) et le pied ({@link #FOOT}) se lisent SANS expression régulière : la répétition d'un groupe
     * ({@code (?:[^\n]*\n)*}) est récursive dans {@code java.util.regex} et débordait la pile dès ~5 000 lignes (bien en deçà de {@link #MAX_MANIFEST_BYTES}).
     */
    private static final Pattern HEAD = Pattern.compile("\\{\n\"schema\":([0-9]+),\n\"version\":\"([^\"]*)\",\n\"sequence\":([0-9]{1,15}),\n\"createdAt\":\"([^\"]*)\",\n\"expires\":\"([^\"]*)\",\n\"artifacts\":\\[\n");
    private static final String FOOT = "]\n}\n";
    private static final Pattern LINE = Pattern.compile("\\{\"name\":\"([^\"]*)\",\"arch\":\"([^\"]*)\",\"file\":\"([^\"]*)\",\"sha256\":\"([^\"]*)\",\"size\":([0-9]{1,15})\\},?");

    /**
     * Lit le format exact produit par {@link #toJson()} (et bin/sign-release.sh) ; tout autre texte est refusé, et le résultat doit se re-sérialiser à l'identique (aucune
     * ambiguïté entre ce que signe l'éditeur et ce que lit le nœud). À n'appeler qu'après la vérification de la signature.
     */
    public static ReleaseManifest parse(byte[] bytes) {
        return parse(bytes, NAMES::contains);
    }

    /** Comme {@link #parse(byte[])} avec le critère de noms de composants de l'appelant (voir {@link #validate(java.util.function.Predicate)}). */
    public static ReleaseManifest parse(byte[] bytes, java.util.function.Predicate<String> nameOk) {
        if (bytes.length > MAX_MANIFEST_BYTES) throw new Invalid("manifeste trop volumineux"); // borne la mémoire et le travail de l'analyse
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = HEAD.matcher(text);
        if (!m.lookingAt() || !text.endsWith(FOOT) || text.length() - FOOT.length() < m.end()) throw new Invalid("manifeste illisible");
        String body = text.substring(m.end(), text.length() - FOOT.length()); // lignes complètes (terminées par \n) ou rien
        if (!body.isEmpty() && body.charAt(body.length() - 1) != '\n') throw new Invalid("manifeste illisible");
        List<Artifact> list = new java.util.ArrayList<>();
        for (String line : body.isEmpty() ? new String[0] : body.split("\n")) {
            java.util.regex.Matcher a = LINE.matcher(line);
            if (!a.matches()) throw new Invalid("ligne d'artefact illisible");
            list.add(new Artifact(a.group(1), a.group(2), a.group(3), a.group(4), Long.parseLong(a.group(5))));
        }
        ReleaseManifest r = new ReleaseManifest(Integer.parseInt(m.group(1)), m.group(2), Long.parseLong(m.group(3)), m.group(4), m.group(5), list);
        r.validate(nameOk);
        if (!r.toJson().equals(text)) throw new Invalid("manifeste non canonique");
        return r;
    }

    /** Artefact d'un composant pour une architecture (ou « all »). */
    public java.util.Optional<Artifact> find(String name, String arch) {
        return artifacts.stream().filter(a -> a.name().equals(name) && (a.arch().equals(arch) || a.arch().equals("all"))).findFirst();
    }

    /**
     * Texte du manifeste, un artefact par ligne dans l'ordre fixe des clés : c'est ce format que lit install.sh (sed), et que produit bin/sign-release.sh ; le signataire Java
     * ({@link ReleaseSigner}) produit les mêmes octets.
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
