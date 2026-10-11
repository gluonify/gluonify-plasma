package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReleaseTest {
    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");

    @TempDir Path dir;

    static KeyPair keys() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    ReleaseManifest manifest() throws Exception {
        Files.writeString(dir.resolve("gluonify-strange-linux-amd64"), "binaire strange amd64");
        Files.writeString(dir.resolve("gluonify-strange-linux-arm64"), "binaire strange arm64");
        Files.writeString(dir.resolve("gluonify-node-image.tar.gz"), "image");
        Files.writeString(dir.resolve("autre.txt"), "ignoré");
        return ReleaseSigner.build(dir, "1.2.3", 7, NOW, NOW.plusSeconds(86400 * 90));
    }

    @Test void construireSignerVerifier() throws Exception {
        KeyPair k = keys();
        ReleaseManifest m = manifest();
        assertEquals(3, m.artifacts().size());
        byte[] bytes = m.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] sig = ReleaseSigner.signBase64(bytes, k.getPrivate()).getBytes(StandardCharsets.US_ASCII);
        ReleaseManifest v = ReleaseVerifier.verify(bytes, sig, k.getPublic(), ReleaseManifest::parse, 7, NOW);
        assertEquals(m, v);
        assertEquals("gluonify-strange-linux-arm64", v.find("gluonify-strange", "arm64").orElseThrow().file());
        assertEquals("gluonify-node-image.tar.gz", v.find("gluonify-node-image", "arm64").orElseThrow().file());
        // signature brute de 64 octets acceptée aussi
        assertTrue(ReleaseVerifier.signatureValid(bytes, ReleaseSigner.sign(bytes, k.getPrivate()), k.getPublic()));
    }

    @Test void manifesteAltereOuMauvaiseCleRefuse() throws Exception {
        KeyPair k = keys();
        byte[] bytes = manifest().toJson().getBytes(StandardCharsets.UTF_8);
        byte[] sig = ReleaseSigner.sign(bytes, k.getPrivate());
        byte[] altered = new String(bytes, StandardCharsets.UTF_8).replace("1.2.3", "1.2.4").getBytes(StandardCharsets.UTF_8);
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(altered, sig, k.getPublic(), ReleaseManifest::parse, 0, NOW));
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(bytes, sig, keys().getPublic(), ReleaseManifest::parse, 0, NOW));
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(bytes, new byte[]{1, 2, 3}, k.getPublic(), ReleaseManifest::parse, 0, NOW));
    }

    @Test void retourEnArriereEtExpirationRefuses() throws Exception {
        KeyPair k = keys();
        byte[] bytes = manifest().toJson().getBytes(StandardCharsets.UTF_8);
        byte[] sig = ReleaseSigner.sign(bytes, k.getPrivate());
        var e = assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(bytes, sig, k.getPublic(), ReleaseManifest::parse, 8, NOW));
        assertTrue(e.getMessage().contains("retour en arrière"));
        e = assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(bytes, sig, k.getPublic(), ReleaseManifest::parse, 1, NOW.plusSeconds(86400L * 91)));
        assertTrue(e.getMessage().contains("expiré"));
    }

    @Test void empreinteEtTailleDesFichiers() throws Exception {
        ReleaseManifest m = manifest();
        var a = m.find("gluonify-strange", "amd64").orElseThrow();
        ReleaseVerifier.checkFile(dir.resolve(a.file()), a);
        Files.writeString(dir.resolve(a.file()), "binaire strange amd64!");
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.checkFile(dir.resolve(a.file()), a));
        Files.writeString(dir.resolve(a.file()), "binaire strange amd6X"); // même taille, autre contenu
        var e = assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.checkFile(dir.resolve(a.file()), a));
        assertTrue(e.getMessage().contains("SHA-256"));
    }

    static ReleaseManifest with(String file, String name, String sha) {
        return new ReleaseManifest(1, "1.0.0", 1, NOW.toString(), NOW.plusSeconds(10).toString(), List.of(new ReleaseManifest.Artifact(name, "amd64", file, sha, 10)));
    }

    @Test void nomsDartefactsDangereuxRefuses() {
        String sha = "a".repeat(64);
        for (String bad : List.of("../etc/passwd", "a/b", "/abs", ".cache", "a..b", "a b", "x;rm", "", "a\\b", "http://evil/x", "a".repeat(200)))
            assertThrows(ReleaseManifest.Invalid.class, () -> with(bad, "gluonify-strange", sha).validate(), bad);
        assertThrows(ReleaseManifest.Invalid.class, () -> with("ok", "evil", sha).validate());
        assertThrows(ReleaseManifest.Invalid.class, () -> with("ok", "gluonify-strange", "ABC").validate());
        with("gluonify-strange-linux-amd64", "gluonify-strange", sha).validate();
    }

    @Test void critereDeNomsChoisiParLAppelant() {
        String sha = "a".repeat(64);
        var nouveau = with("gluonify-nouveau-linux-arm64.gz", "gluonify-nouveau", sha);
        assertThrows(ReleaseManifest.Invalid.class, () -> nouveau.validate(), "un client garde sa liste fermée : il n'installe que ce qu'il connaît");
        nouveau.validate(n -> ReleaseManifest.SAFE_NAME.matcher(n).matches()); // le réservoir accepte tout nom sûr
        var dangereux = with("ok", "../evil", sha);
        assertThrows(ReleaseManifest.Invalid.class, () -> dangereux.validate(n -> ReleaseManifest.SAFE_NAME.matcher(n).matches()));
        assertThrows(ReleaseManifest.Invalid.class, () -> with("ok", "Evil", sha).validate(n -> ReleaseManifest.SAFE_NAME.matcher(n).matches()));
    }

    @Test void gdownEstUnComposantDeLaRelease() {
        String sha = "a".repeat(64);
        with("gluonify-gdown-linux-arm64.gz", "gluonify-gdown", sha).validate(); // gDown seul (gDown.sh) : même chaîne de confiance
        assertThrows(ReleaseManifest.Invalid.class, () -> with("ok", "gluonify-graphdb", sha).validate());
    }

    @Test void manifesteNonCanoniqueRefuse() throws Exception {
        String json = manifest().toJson();
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse((json + " ").getBytes(StandardCharsets.UTF_8)));
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(json.replace("\"schema\":1", "\"schema\":2").getBytes(StandardCharsets.UTF_8)));
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void clePubliquePem() throws Exception {
        KeyPair k = keys();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(k.getPublic().getEncoded()) + "\n-----END PUBLIC KEY-----\n";
        assertEquals(k.getPublic(), ReleaseVerifier.publicKey(pem));
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.publicKey("pas une clé"));
    }

    /** Manifeste produit par bin/sign-release.sh (openssl) avec une clé de test jetable : la vérification Java accepte les mêmes octets. */
    @Test void manifesteProduitParLeScriptShellVerifieParJava() throws Exception {
        byte[] m = getClass().getResourceAsStream("/release/manifest.json").readAllBytes();
        byte[] sig = getClass().getResourceAsStream("/release/manifest.json.sig").readAllBytes();
        String pem = new String(getClass().getResourceAsStream("/release/release.pub").readAllBytes(), StandardCharsets.US_ASCII);
        ReleaseManifest v = ReleaseVerifier.verify(m, sig, ReleaseVerifier.publicKey(pem), ReleaseManifest::parse, 7, NOW);
        assertEquals("1.2.3", v.version());
        assertEquals(7, v.sequence());
        assertTrue(v.find("gluonify-node-image", "arm64").isPresent());
        // un octet changé : refusé
        m[m.length - 3] ^= 1;
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(m, sig, ReleaseVerifier.publicKey(pem), ReleaseManifest::parse, 7, NOW));
    }

    @Test
    void oversizedManifestIsRefusedBeforeParsing() {
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(new byte[2 * 1024 * 1024]));
    }

    /** Sonar java:S5998 : l'ancienne expression régulière répétait un groupe (une récursion par ligne) et débordait la pile dès ~5 000 lignes vides, très en deçà de la borne de taille. */
    @Test
    void manyEmptyLinesAreRefusedWithoutStackOverflow() {
        String head = "{\n\"schema\":1,\n\"version\":\"1.0.0\",\n\"sequence\":1,\n\"createdAt\":\"2026-10-01T00:00:00Z\",\n\"expires\":\"2026-10-02T00:00:00Z\",\n\"artifacts\":[\n";
        String text = head + "\n".repeat(ReleaseManifest.MAX_MANIFEST_BYTES - head.length() - 4) + "]\n}\n";
        assertEquals(ReleaseManifest.MAX_MANIFEST_BYTES, text.length());
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void manifestWithoutFootOrWithPartialLastLineIsRefused() throws Exception {
        String json = manifest().toJson();
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(json.substring(0, json.length() - 2).getBytes(StandardCharsets.UTF_8)), "pied tronqué");
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(json.replace("}\n]\n}\n", "}]\n}\n").getBytes(StandardCharsets.UTF_8)), "dernière ligne sans fin de ligne");
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseManifest.parse(json.replace("\"artifacts\":[\n", "\"artifacts\":[\n\n").getBytes(StandardCharsets.UTF_8)), "ligne vide dans la liste");
        assertEquals(manifest(), ReleaseManifest.parse(json.getBytes(StandardCharsets.UTF_8)), "le format canonique reste lu à l'identique");
    }

    @Test
    void signedComparesByContent() {
        var a = new GzipSignature.Signed(new byte[] {1, 2}, new byte[] {3});
        var b = new GzipSignature.Signed(new byte[] {1, 2}, new byte[] {3});
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new GzipSignature.Signed(new byte[] {1, 2}, new byte[] {4}));
    }
}
