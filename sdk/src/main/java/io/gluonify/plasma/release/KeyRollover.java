package io.gluonify.plasma.release;

import java.util.regex.Pattern;

/**
 * Annonce de changement de clé de publication (voir gluonify-codex/docs/BOOTSTRAP.md §11 ter). Fichier {@code rollover.json} d'une version, SIGNÉ par la clé de publication
 * actuelle ({@code rollover.json.sig}) et contresigné par la NOUVELLE clé ({@code rollover.json.sig2} : preuve que la nouvelle clé existe et que son détenteur est d'accord).
 * Il est séparé de {@code manifest.json} À DESSEIN : le format du manifeste est strict (un nœud ou un install.sh de la version 0.1.0 refuse tout champ ajouté), donc l'annonce
 * ne peut pas y figurer sans empêcher les anciens nœuds de lire la version de transition. Elle est liée au manifeste par son empreinte ({@code manifestSha256}).
 *
 * @param schema         version du format (1)
 * @param version        version de la distribution qui porte l'annonce (celle du manifeste)
 * @param sequence       séquence du manifeste (identique)
 * @param manifestSha256 empreinte SHA-256 des octets de {@code manifest.json} de cette version
 * @param createdAt      date de l'annonce (ISO-8601)
 * @param nextKey        nouvelle clé publique Ed25519, SubjectPublicKeyInfo DER en Base64 sur une ligne
 * @param nextKeySha256  empreinte SHA-256 de ce DER (celle que les clients passent à {@code --key-sha256})
 * @param retireOldAfter à partir de cette date, un manifeste signé SEULEMENT par l'ancienne clé est refusé (fin du recouvrement)
 */
public record KeyRollover(int schema, String version, long sequence, String manifestSha256, String createdAt, String nextKey, String nextKeySha256, String retireOldAfter) {
    public static final int SCHEMA = 1;
    /** Recouvrement maximal accepté entre l'annonce et la fin de l'ancienne clé. */
    public static final java.time.Duration MAX_OVERLAP = java.time.Duration.ofDays(400);
    public static final int MAX_BYTES = 4096;
    static final Pattern B64 = Pattern.compile("[A-Za-z0-9+/]{40,120}={0,2}");
    static final Pattern SHA = Pattern.compile("[0-9a-f]{64}");

    private static final Pattern FORMAT = Pattern.compile("\\{\n\"schema\":([0-9]+),\n\"version\":\"([^\"]*)\",\n\"sequence\":([0-9]{1,15}),\n\"manifestSha256\":\"([^\"]*)\",\n\"createdAt\":\"([^\"]*)\",\n"
            + "\"nextKey\":\"([^\"]*)\",\n\"nextKeySha256\":\"([^\"]*)\",\n\"retireOldAfter\":\"([^\"]*)\"\n\\}\n");

    /** Contrôle structurel ; la clé doit être un Ed25519 lisible dont l'empreinte est celle annoncée. */
    public KeyRollover validate() {
        if (schema != SCHEMA) throw new ReleaseManifest.Invalid("schéma d'annonce de clé non pris en charge : " + schema);
        if (version == null || !ReleaseManifest.VERSION.matcher(version).matches()) throw new ReleaseManifest.Invalid("annonce : version invalide");
        if (sequence < 1) throw new ReleaseManifest.Invalid("annonce : séquence invalide");
        if (manifestSha256 == null || !SHA.matcher(manifestSha256).matches()) throw new ReleaseManifest.Invalid("annonce : empreinte du manifeste invalide");
        java.time.Instant created, retire;
        try {
            created = java.time.Instant.parse(createdAt);
            retire = java.time.Instant.parse(retireOldAfter);
        } catch (RuntimeException e) {
            throw new ReleaseManifest.Invalid("annonce : date invalide");
        }
        if (!retire.isAfter(created) || retire.isAfter(created.plus(MAX_OVERLAP))) throw new ReleaseManifest.Invalid("annonce : fin de recouvrement hors limites");
        if (nextKey == null || !B64.matcher(nextKey).matches()) throw new ReleaseManifest.Invalid("annonce : clé invalide");
        TrustedKeys.Key k = TrustedKeys.Key.ofDer(nextKey); // lève Invalid si ce n'est pas un Ed25519
        if (nextKeySha256 == null || !k.sha256().equals(nextKeySha256)) throw new ReleaseManifest.Invalid("annonce : l'empreinte ne correspond pas à la clé");
        return this;
    }

    public java.time.Instant retireAt() {
        return java.time.Instant.parse(retireOldAfter);
    }

    public TrustedKeys.Key key() {
        return TrustedKeys.Key.ofDer(nextKey);
    }

    /** Lit le format exact de {@link #toJson()} (re-sérialisation identique exigée) ; à n'appeler qu'APRÈS la vérification des signatures. */
    public static KeyRollover parse(byte[] bytes) {
        if (bytes.length > MAX_BYTES) throw new ReleaseManifest.Invalid("annonce de clé trop volumineuse");
        String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        java.util.regex.Matcher m = FORMAT.matcher(text);
        if (!m.matches()) throw new ReleaseManifest.Invalid("annonce de clé illisible");
        KeyRollover r = new KeyRollover(Integer.parseInt(m.group(1)), m.group(2), Long.parseLong(m.group(3)), m.group(4), m.group(5), m.group(6), m.group(7), m.group(8));
        r.validate();
        if (!r.toJson().equals(text)) throw new ReleaseManifest.Invalid("annonce de clé non canonique");
        return r;
    }

    /** Même format que bin/sign-release.sh --next-key. */
    public String toJson() {
        return "{\n\"schema\":" + schema + ",\n\"version\":\"" + version + "\",\n\"sequence\":" + sequence + ",\n\"manifestSha256\":\"" + manifestSha256 + "\",\n\"createdAt\":\"" + createdAt
                + "\",\n\"nextKey\":\"" + nextKey + "\",\n\"nextKeySha256\":\"" + nextKeySha256 + "\",\n\"retireOldAfter\":\"" + retireOldAfter + "\"\n}\n";
    }
}
