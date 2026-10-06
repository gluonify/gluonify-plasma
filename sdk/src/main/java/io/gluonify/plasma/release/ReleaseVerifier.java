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
 * Vérifications de confiance d'une version : signature Ed25519 du manifeste, expiration, séquence (anti-retour en arrière), empreinte SHA-256 des fichiers.
 * La clé publique est au format PEM « PUBLIC KEY » (SubjectPublicKeyInfo), celui que produit {@code openssl pkey -pubout}.
 */
public final class ReleaseVerifier {
    private ReleaseVerifier() {}

    public static PublicKey publicKey(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new ReleaseManifest.Invalid("clé publique illisible");
        }
    }

    /** Signature détachée : 64 octets bruts, ou leur encodage Base64 (fichier .sig texte). */
    static byte[] decodeSignature(byte[] sig) {
        if (sig.length == 64) return sig;
        try {
            byte[] raw = Base64.getMimeDecoder().decode(new String(sig, java.nio.charset.StandardCharsets.US_ASCII).trim());
            if (raw.length == 64) return raw;
        } catch (IllegalArgumentException ignored) { }
        throw new ReleaseManifest.Invalid("signature illisible");
    }

    public static boolean signatureValid(byte[] manifest, byte[] signature, PublicKey key) {
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
     * Vérifie la signature, lit le manifeste avec {@code parser} puis contrôle sa structure, son expiration et sa séquence. Rien du contenu n'est utilisé avant la signature.
     *
     * @param minSequence plus haute séquence déjà vue (ou exigée) ; le manifeste doit être au moins à ce niveau
     */
    public static ReleaseManifest verify(byte[] manifest, byte[] signature, PublicKey key, java.util.function.Function<byte[], ReleaseManifest> parser, long minSequence, Instant now) {
        if (!signatureValid(manifest, signature, key)) throw new ReleaseManifest.Invalid("signature du manifeste invalide");
        ReleaseManifest m = parser.apply(manifest).validate();
        if (now.isAfter(Instant.parse(m.expires()))) throw new ReleaseManifest.Invalid("manifeste expiré (" + m.expires() + ")");
        if (m.sequence() < minSequence) throw new ReleaseManifest.Invalid("retour en arrière refusé : séquence " + m.sequence() + " < " + minSequence);
        return m;
    }

    /** SHA-256 hexadécimal d'octets en mémoire. */
    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Le fichier téléchargé a la taille et l'empreinte déclarées (comparaison à temps constant). */
    public static void checkFile(Path file, ReleaseManifest.Artifact a) throws IOException {
        if (Files.size(file) != a.size()) throw new ReleaseManifest.Invalid("taille inattendue pour " + a.file());
        if (!MessageDigest.isEqual(sha256(file).getBytes(), a.sha256().getBytes())) throw new ReleaseManifest.Invalid("empreinte SHA-256 invalide pour " + a.file());
    }
}
