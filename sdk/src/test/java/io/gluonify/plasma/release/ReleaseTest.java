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
}
