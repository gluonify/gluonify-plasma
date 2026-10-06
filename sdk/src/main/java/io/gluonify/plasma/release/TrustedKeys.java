package io.gluonify.plasma.release;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Ensemble des clés de publication qu'un nœud (ou sea) accepte, et règles de rotation (voir gluonify-codex/docs/BOOTSTRAP.md §11 ter).
 * <ul>
 *   <li>{@code current} : la clé de confiance ; {@code next} : pendant le recouvrement, la clé annoncée par une version signée de {@code current} ;
 *       {@code retireCurrentAfter} : à partir de cette date, {@code current} n'est plus acceptée (anti-retour en arrière) ;</li>
 *   <li>{@code sequence} : séquence de l'annonce qui a produit cet état (0 : état d'origine, un seul fichier {@code release.pub}) ; une nouvelle annonce doit la dépasser.</li>
 * </ul>
 * Objet immuable : chaque étape rend un nouvel ensemble, que l'appelant écrit atomiquement.
 */
public record TrustedKeys(Key current, Key next, Instant retireCurrentAfter, long sequence) {
    /** @param sha256 empreinte SHA-256 du DER (celle de {@code --key-sha256}) ; @param der SubjectPublicKeyInfo en Base64. */
    public record Key(String sha256, String der) {
        static final Pattern B64 = Pattern.compile("[A-Za-z0-9+/]{40,120}={0,2}");

        public static Key ofDer(String b64) {
            if (b64 == null || !B64.matcher(b64).matches()) throw new ReleaseManifest.Invalid("clé publique illisible");
            byte[] d;
            try {
                d = Base64.getDecoder().decode(b64);
                java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(d));
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                throw new ReleaseManifest.Invalid("clé publique illisible");
            }
            return new Key(sha(d), b64);
        }

        public static Key ofPem(String pem) {
            PublicKey k = ReleaseVerifier.publicKey(pem);
            return ofDer(Base64.getEncoder().encodeToString(k.getEncoded()));
        }

        public PublicKey publicKey() {
            try {
                return java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(der)));
            } catch (java.security.GeneralSecurityException e) {
                throw new ReleaseManifest.Invalid("clé publique illisible");
            }
        }

        public String pem() {
            return "-----BEGIN PUBLIC KEY-----\n" + der.replaceAll("(.{64})", "$1\n").trim() + "\n-----END PUBLIC KEY-----\n";
        }
    }

    static String sha(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String HEADER = "gluonify-release-keys 1";
    private static final Pattern SHA_P = Pattern.compile("[0-9a-f]{64}");

    /** État d'origine : une seule clé (le fichier {@code release.pub} d'un nœud installé avant la rotation). */
    public static TrustedKeys single(Key k) {
        return new TrustedKeys(k, null, null, 0);
    }

    public TrustedKeys {
        if (current == null) throw new ReleaseManifest.Invalid("aucune clé de confiance");
        if ((next == null) != (retireCurrentAfter == null)) throw new ReleaseManifest.Invalid("clés : recouvrement incohérent");
    }

    /** Clés acceptées à {@code now} : {@code current} jusqu'à la date de bascule, {@code next} pendant le recouvrement. */
    public List<Key> accepted(Instant now) {
        List<Key> out = new ArrayList<>();
        if (next == null || now.isBefore(retireCurrentAfter)) out.add(current);
        if (next != null) out.add(next);
        return out;
    }

    // ------------------------------------------------------------------ fichier

    public String toText() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n').append("sequence ").append(sequence).append('\n');
        sb.append("current ").append(current.sha256()).append(' ').append(current.der()).append('\n');
        if (next != null) sb.append("next ").append(next.sha256()).append(' ').append(next.der()).append(' ').append(retireCurrentAfter).append('\n');
        return sb.toString();
    }

    /** Lecture stricte du fichier {@code release.keys} ; toute ligne inattendue ou empreinte incohérente est refusée. */
    public static TrustedKeys parse(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || !lines[0].equals(HEADER)) throw new ReleaseManifest.Invalid("fichier de clés illisible");
        if (!lines[1].matches("sequence [0-9]{1,15}")) throw new ReleaseManifest.Invalid("fichier de clés : séquence illisible");
        long seq = Long.parseLong(lines[1].substring(9));
        Key cur = null, nxt = null;
        Instant retire = null;
        int i = 2;
        String[] p = lines[i++].split(" ");
        if (p.length != 3 || !p[0].equals("current")) throw new ReleaseManifest.Invalid("fichier de clés : clé courante illisible");
        cur = checked(p[1], p[2]);
        if (i < lines.length && lines[i].startsWith("next ")) {
            p = lines[i++].split(" ");
            if (p.length != 4) throw new ReleaseManifest.Invalid("fichier de clés : clé suivante illisible");
            nxt = checked(p[1], p[2]);
            try {
                retire = Instant.parse(p[3]);
            } catch (RuntimeException e) {
                throw new ReleaseManifest.Invalid("fichier de clés : date illisible");
            }
        }
        if (i != lines.length - 1 || !lines[i].isEmpty()) throw new ReleaseManifest.Invalid("fichier de clés : contenu inattendu");
        return new TrustedKeys(cur, nxt, retire, seq);
    }

    private static Key checked(String sha, String der) {
        if (!SHA_P.matcher(sha).matches()) throw new ReleaseManifest.Invalid("fichier de clés : empreinte invalide");
        Key k = Key.ofDer(der);
        if (!k.sha256().equals(sha)) throw new ReleaseManifest.Invalid("fichier de clés : empreinte incohérente");
        return k;
    }

    // ------------------------------------------------------------------ vérification

    /** Manifeste vérifié et clé qui l'a signé. */
    public record Verified(ReleaseManifest manifest, Key signer, boolean viaSig2) {}

    /**
     * Vérifie le manifeste avec les clés acceptées à {@code now} : {@code sig} d'abord, puis {@code sig2} (contresignature de la nouvelle clé, facultative). Structure, expiration et
     * séquence ({@code >= minSequence}) comme {@link ReleaseVerifier#verify}. Un manifeste signé SEULEMENT par la clé retirée est refusé avec un message explicite.
     */
    public Verified verify(byte[] manifest, byte[] sig, byte[] sig2, java.util.function.Function<byte[], ReleaseManifest> parser, long minSequence, Instant now) {
        List<Key> ok = accepted(now);
        for (int pass = 0; pass < 2; pass++) {
            byte[] s = pass == 0 ? sig : sig2;
            if (s == null) continue;
            for (Key k : ok) {
                boolean valid;
                try {
                    valid = ReleaseVerifier.signatureValid(manifest, s, k.publicKey());
                } catch (ReleaseManifest.Invalid e) {
                    valid = false;
                }
                if (valid) return new Verified(ReleaseVerifier.verify(manifest, s, k.publicKey(), parser, minSequence, now), k, pass == 1);
            }
        }
        if (next != null && !ok.contains(current)) {
            try {
                if (sig != null && ReleaseVerifier.signatureValid(manifest, sig, current.publicKey()))
                    throw new ReleaseManifest.Invalid("clé de publication retirée depuis " + retireCurrentAfter + " : manifeste signé seulement par l'ancienne clé refusé (retour en arrière)");
            } catch (ReleaseManifest.Invalid e) {
                if (e.getMessage().startsWith("clé de publication retirée")) throw e;
            }
        }
        throw new ReleaseManifest.Invalid("signature du manifeste invalide");
    }

    /** Résultat d'une annonce acceptée : nouvel ensemble, et {@code changed=false} si l'état annonçait déjà cette clé (idempotence). */
    public record Applied(TrustedKeys keys, boolean changed, ReleaseManifest manifest) {}

    /**
     * Règles d'acceptation d'un changement de clé (TOUTES requises, sinon {@link ReleaseManifest.Invalid}) : (1) le manifeste est valide (signature {@code sig}) sous une clé
     * actuellement acceptée, non expiré, séquence {@code >= highestSeen} ; (2) l'annonce est signée par CETTE MÊME clé, liée au manifeste (empreinte, version, séquence) ;
     * (3) la nouvelle clé a contresigné l'annonce ({@code rolloverSig2}) et, si présente, le manifeste ({@code sig2}) ; (4) la séquence dépasse celle du dernier changement de clé ;
     * (5) la nouvelle clé diffère de la clé qui signe, la date de bascule est dans le futur ; (6) pas de rotation concurrente : si une clé suivante est déjà installée, c'est la même.
     */
    public Applied applyRollover(byte[] manifest, byte[] sig, byte[] sig2, byte[] rollover, byte[] rolloverSig, byte[] rolloverSig2, long highestSeen, Instant now) {
        if (sig == null || rollover == null || rolloverSig == null || rolloverSig2 == null) throw new ReleaseManifest.Invalid("annonce de clé incomplète (manifeste, annonce et contresignature requis)");
        Verified v = verify(manifest, sig, null, ReleaseManifest::parse, highestSeen, now); // seule la signature principale, jamais sig2, porte l'annonce
        if (!ReleaseVerifier.signatureValid(rollover, rolloverSig, v.signer().publicKey())) throw new ReleaseManifest.Invalid("annonce de clé : signature invalide (pas celle de la clé qui signe le manifeste)");
        KeyRollover r = KeyRollover.parse(rollover);
        if (!r.version().equals(v.manifest().version()) || r.sequence() != v.manifest().sequence()) throw new ReleaseManifest.Invalid("annonce de clé : version ou séquence différentes du manifeste");
        if (!r.manifestSha256().equals(sha(manifest))) throw new ReleaseManifest.Invalid("annonce de clé : non liée à ce manifeste");
        Key nk = r.key();
        if (nk.sha256().equals(v.signer().sha256())) throw new ReleaseManifest.Invalid("annonce de clé : la nouvelle clé est celle qui signe");
        if (!ReleaseVerifier.signatureValid(rollover, rolloverSig2, nk.publicKey())) throw new ReleaseManifest.Invalid("annonce de clé : contresignature de la nouvelle clé invalide");
        if (sig2 != null && !ReleaseVerifier.signatureValid(manifest, sig2, nk.publicKey())) throw new ReleaseManifest.Invalid("manifeste : contresignature de la nouvelle clé invalide");
        if (next != null && next.sha256().equals(nk.sha256())) return new Applied(this, false, v.manifest()); // déjà installée
        if (current.sha256().equals(nk.sha256())) return new Applied(this, false, v.manifest());
        if (next != null) throw new ReleaseManifest.Invalid("rotation déjà en cours vers une autre clé (la terminer d'abord)");
        if (r.sequence() <= sequence) throw new ReleaseManifest.Invalid("annonce de clé rejouée : séquence " + r.sequence() + " <= " + sequence + " (dernier changement de clé)");
        if (!r.retireAt().isAfter(now)) throw new ReleaseManifest.Invalid("annonce de clé : la date de bascule est passée");
        return new Applied(new TrustedKeys(current, nk, r.retireAt(), r.sequence()), true, v.manifest());
    }

    /**
     * Termine la rotation : la clé suivante devient la seule clé (l'ancienne est retirée). Exige un manifeste valide signé par la clé SUIVANTE (preuve qu'elle sert en réel),
     * non expiré, séquence {@code >= highestSeen}.
     */
    public Applied finish(byte[] manifest, byte[] sig, byte[] sig2, long highestSeen, Instant now) {
        if (next == null) return new Applied(this, false, null); // rien à terminer : déjà seule clé
        TrustedKeys onlyNext = new TrustedKeys(next, null, null, sequence);
        Verified v = onlyNext.verify(manifest, sig, sig2, ReleaseManifest::parse, highestSeen, now);
        return new Applied(new TrustedKeys(next, null, null, sequence), true, v.manifest());
    }
}
