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
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jeton de provenance : {@code base64url(octets canoniques de la déclaration) "." base64url(signature Ed25519 de 64 octets)}, sans remplissage. Une seule chaîne, portée
 * telle quelle par {@code AppSpec.provenance} (état Raft, historique des versions, retour arrière), {@code DeployRequest.provenance}, la configuration de la pile ou un fichier
 * {@code .sig}. Signature et vérification par le JDK ({@code Ed25519}), sans dépendance.
 */
public final class Provenance {
    private Provenance() {}

    /** Longueur maximale d'un jeton (déclaration de 2048 octets au plus, signature de 64). */
    public static final int MAX_TOKEN = 2840;
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{8,2800}\\.[A-Za-z0-9_-]{86}");
    private static final Pattern ARTIFACT_SHA = Pattern.compile("#sha256=([0-9a-fA-F]{64})$");
    private static final Pattern IMAGE_DIGEST = Pattern.compile("@(sha256:[0-9a-f]{64})$");

    /** Jeton décodé : déclaration (lue après la vérification seulement, voir {@link #verify}), octets signés et signature. */
    public record Signed(ProvenanceStatement statement, byte[] statementBytes, byte[] signature) {}

    /** Forme valide d'un jeton (sans vérifier la signature) : c'est ce que contrôle {@code AppSpec}. */
    public static boolean wellFormed(String token) {
        return token != null && token.length() <= MAX_TOKEN && TOKEN.matcher(token).matches();
    }

    /** Signe la déclaration avec une clé privée Ed25519 et rend le jeton. */
    public static String sign(ProvenanceStatement s, PrivateKey key) {
        byte[] b = s.canonicalBytes();
        try {
            Signature sig = Signature.getInstance("Ed25519");
            sig.initSign(key);
            sig.update(b);
            Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
            return enc.encodeToString(b) + "." + enc.encodeToString(sig.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("provenance : signature impossible (clé Ed25519 attendue)", e);
        }
    }

    /** Découpe le jeton sans rien vérifier ; la déclaration n'est PAS lue ici (elle ne l'est qu'après la signature). */
    static byte[][] split(String token) {
        if (!wellFormed(token)) throw new IllegalArgumentException("provenance : jeton illisible");
        int dot = token.indexOf('.');
        try {
            Base64.Decoder dec = Base64.getUrlDecoder();
            byte[] b = dec.decode(token.substring(0, dot));
            byte[] s = dec.decode(token.substring(dot + 1));
            if (s.length != 64 || b.length > ProvenanceStatement.MAX_BYTES) throw new IllegalArgumentException("provenance : jeton illisible");
            return new byte[][] {b, s};
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provenance : jeton illisible");
        }
    }

    /**
     * Identifiant de clé annoncé par le jeton, lu SANS vérification (pour choisir la clé publique) ; à ne jamais utiliser pour une décision. Null si illisible.
     */
    public static String claimedKeyId(String token) {
        try {
            return ProvenanceStatement.parse(split(token)[0]).keyId();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Vérifie la signature avec {@code key}, puis lit la déclaration. {@link IllegalArgumentException} si le jeton est illisible ou la signature fausse. */
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
        if (!ok) throw new IllegalArgumentException("provenance : signature invalide");
        return new Signed(ProvenanceStatement.parse(p[0]), p[0], p[1]);
    }

    /**
     * La déclaration couvre-t-elle exactement les livrables de l'application {@code app} ? Nom identique ; avec des exécutables : chaque architecture de la spécification
     * a la même empreinte dans la déclaration (la déclaration peut en lister d'autres) ; sans exécutable : l'image est désignée par la même empreinte. Rend la raison du
     * refus, ou null.
     */
    public static String mismatch(ProvenanceStatement s, String app, Map<String, String> artifacts, String image) {
        if (!s.app().equals(app)) return "la déclaration est pour une autre application";
        if (artifacts != null && !artifacts.isEmpty()) {
            for (var e : artifacts.entrySet()) {
                String sha = artifactSha256(e.getValue());
                if (sha == null) return "exécutable " + e.getKey() + " sans empreinte #sha256=";
                if (!sha.equals(s.artifacts().get(e.getKey()))) return "empreinte de l'exécutable " + e.getKey() + " non couverte par la déclaration";
            }
            return null;
        }
        String d = imageDigest(image);
        if (d == null) return "image sans empreinte (@sha256:…)";
        if (!d.equals(s.imageDigest())) return "empreinte de l'image non couverte par la déclaration";
        return null;
    }

    /** SHA-256 (minuscules) d'une référence d'exécutable {@code url#sha256=<hexa>}, ou null. */
    public static String artifactSha256(String ref) {
        if (ref == null) return null;
        Matcher m = ARTIFACT_SHA.matcher(ref);
        return m.find() ? m.group(1).toLowerCase(java.util.Locale.ROOT) : null;
    }

    /** Empreinte {@code sha256:<hexa>} d'une image désignée par {@code dépôt@sha256:<hexa>}, ou null (étiquette seule). */
    public static String imageDigest(String image) {
        if (image == null) return null;
        Matcher m = IMAGE_DIGEST.matcher(image.trim());
        return m.find() ? m.group(1) : null;
    }

    /** Vrai si les deux spécifications exécutent les mêmes octets : mêmes architectures et mêmes empreintes, ou même image (les URL peuvent différer). */
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

    // ------------------------------------------------------------------ clés

    /** Clé publique Ed25519 : SubjectPublicKeyInfo en Base64 (DER) ou PEM « PUBLIC KEY ». */
    public static PublicKey publicKey(String text) {
        try {
            String b64 = text.replace("-----BEGIN PUBLIC KEY-----", "").replace("-----END PUBLIC KEY-----", "").replaceAll("\\s", "");
            PublicKey k = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
            return k;
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("provenance : clé publique Ed25519 illisible");
        }
    }

    /** Clé privée Ed25519 : PEM « PRIVATE KEY » (PKCS#8, celui de {@code openssl genpkey -algorithm ed25519}). Le contenu n'apparaît dans aucun message. */
    public static PrivateKey privateKey(String pem) {
        try {
            String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        } catch (GeneralSecurityException | IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("provenance : clé privée Ed25519 illisible");
        }
    }

    /** Lit une clé privée depuis un fichier (PEM PKCS#8) ; le chemin seul figure dans l'erreur. */
    public static PrivateKey readPrivateKey(Path file) throws IOException {
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        try {
            return privateKey(pem);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("provenance : clé privée Ed25519 illisible dans " + file);
        }
    }

    /** Clé publique d'une clé privée Ed25519 (Base64 du DER SubjectPublicKeyInfo), à déclarer dans {@code gluonify.higgs.provenance.trusted-keys.<id>}. */
    public static String publicKeyBase64(PublicKey k) {
        return Base64.getEncoder().encodeToString(k.getEncoded());
    }
}
