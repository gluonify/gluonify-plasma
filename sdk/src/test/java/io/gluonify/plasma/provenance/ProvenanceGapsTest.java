package io.gluonify.plasma.provenance;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Provenance statements and tokens: every field the statement refuses, every way a token or a key can be unreadable, and the comparison of deliverables. */
class ProvenanceGapsTest {
    static final String A = "a".repeat(64);
    static final String B = "b".repeat(64);
    static final Instant AT = Instant.parse("2026-10-11T20:00:00Z");

    @TempDir
    Path dir;

    static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static ProvenanceStatement statement() {
        return new ProvenanceStatement("shop", Map.of("amd64", A), null, "bup-2026", AT);
    }

    static void refused(final Runnable call, final String fragment) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, call::run);
        assertTrue(e.getMessage().contains(fragment), fragment + " expected in " + e.getMessage());
    }

    // --- the statement ---

    @Test
    void everyFieldOfAStatementIsChecked() {
        refused(() -> new ProvenanceStatement(null, Map.of("amd64", A), null, "k", AT), "invalid application name");
        refused(() -> new ProvenanceStatement("Bad App", Map.of("amd64", A), null, "k", AT), "invalid application name");
        refused(() -> new ProvenanceStatement("shop", Map.of("AMD64", A), null, "k", AT), "invalid architecture");
        refused(() -> new ProvenanceStatement("shop", Map.of("amd64", "xyz"), null, "k", AT), "invalid SHA-256 digest for amd64");
        refused(() -> new ProvenanceStatement("shop", Map.of("amd64", A), "sha256:xyz", "k", AT), "invalid image digest");
        refused(() -> new ProvenanceStatement("shop", Map.of(), null, "k", AT), "neither executable nor image");
        refused(() -> new ProvenanceStatement("shop", null, null, "k", AT), "neither executable nor image");
        refused(() -> new ProvenanceStatement("shop", Map.of("amd64", A), null, null, AT), "invalid key identifier");
        refused(() -> new ProvenanceStatement("shop", Map.of("amd64", A), null, "Bad Key", AT), "invalid key identifier");
        refused(() -> new ProvenanceStatement("shop", Map.of("amd64", A), null, "k", null), "signing date missing");
        final Map<String, String> many = new TreeMap<>();
        for (int i = 0; i < 17; i++) {
            many.put("a" + i, A);
        }
        refused(() -> new ProvenanceStatement("shop", many, null, "k", AT), "too many architectures");
        final Map<String, String> nullKey = new HashMap<>();
        nullKey.put(null, A);
        refused(() -> new ProvenanceStatement("shop", nullKey, null, "k", AT), "invalid architecture");
        final Map<String, String> nullValue = new HashMap<>();
        nullValue.put("amd64", null);
        refused(() -> new ProvenanceStatement("shop", nullValue, null, "k", AT), "invalid SHA-256 digest");
        assertEquals(AT, new ProvenanceStatement("shop", Map.of("amd64-v2", A), "sha256:" + A, "k", AT.plusMillis(900)).signedAt(), "seconds only");
    }

    @Test
    void parsingNeedsTheExactCanonicalText() {
        final byte[] canonical = statement().canonicalBytes();
        assertEquals(statement(), ProvenanceStatement.parse(canonical));
        final String text = new String(canonical, StandardCharsets.UTF_8);
        refused(() -> ProvenanceStatement.parse(null), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(new byte[0]), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(new byte[ProvenanceStatement.MAX_BYTES + 1]), "unreadable statement");
        refused(() -> ProvenanceStatement.parse("a\nb\n".getBytes(StandardCharsets.UTF_8)), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(text.replace("gluonify-provenance 1", "gluonify-provenance 2").getBytes(StandardCharsets.UTF_8)), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(text.substring(0, text.length() - 1).getBytes(StandardCharsets.UTF_8)), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(text.replace("app shop", "application shop").getBytes(StandardCharsets.UTF_8)), "line \"app\" expected");
        refused(() -> ProvenanceStatement.parse(text.replace("app shop", "app ").getBytes(StandardCharsets.UTF_8)), "invalid value for \"app\"");
        refused(() -> ProvenanceStatement.parse(text.replace("app shop", "app sh op").getBytes(StandardCharsets.UTF_8)), "invalid value for \"app\"");
        refused(() -> ProvenanceStatement.parse(text.replace("artifact amd64 " + A, "artifact amd64").getBytes(StandardCharsets.UTF_8)), "unreadable artifact line");
        refused(() -> ProvenanceStatement.parse(text.replace("artifact amd64 " + A, "artifact amd64 " + A + "\nartifact amd64 " + B).getBytes(StandardCharsets.UTF_8)), "unreadable artifact line");
        refused(() -> ProvenanceStatement.parse((text + "extra\n").getBytes(StandardCharsets.UTF_8)), "unreadable statement");
        refused(() -> ProvenanceStatement.parse(text.replace("2026-10-11T20:00:00Z", "yesterday").getBytes(StandardCharsets.UTF_8)), "unreadable signing date");
        refused(() -> ProvenanceStatement.parse(text.replace("key bup-2026", "clef bup-2026").getBytes(StandardCharsets.UTF_8)), "line \"key\" expected");
        refused(() -> ProvenanceStatement.parse(text.replace("2026-10-11T20:00:00Z", "2026-10-11T20:00:00.5Z").getBytes(StandardCharsets.UTF_8)), "non-canonical statement");
        final String image = "gluonify-provenance 1\napp shop\nimage sha256:" + A + "\nkey k\nsigned-at 2026-10-11T20:00:00Z\n";
        assertEquals("sha256:" + A, ProvenanceStatement.parse(image.getBytes(StandardCharsets.UTF_8)).imageDigest());
    }

    @Test
    void aStatementIsBuiltFromTheSpecificationsReferences() {
        assertEquals(A, ProvenanceStatement.of("shop", Map.of("amd64", "https://x/y#sha256=" + A.toUpperCase()), null, "k", AT).artifacts().get("amd64"));
        refused(() -> ProvenanceStatement.of("shop", Map.of("amd64", "https://x/y"), null, "k", AT), "without #sha256= digest");
        assertEquals("sha256:" + A, ProvenanceStatement.of("shop", null, "registry/shop@sha256:" + A, "k", AT).imageDigest());
        assertEquals("sha256:" + A, ProvenanceStatement.of("shop", Map.of(), "registry/shop@sha256:" + A, "k", AT).imageDigest());
        refused(() -> ProvenanceStatement.of("shop", null, "registry/shop:1.0", "k", AT), "a tag cannot be signed");
        refused(() -> ProvenanceStatement.of("shop", null, " ", "k", AT), "neither executable nor image");
        assertNull(ProvenanceStatement.of("shop", Map.of("amd64", "u#sha256=" + A), "registry/shop:1.0", "k", AT).imageDigest(), "executables win over the image");
    }

    // --- tokens ---

    @Test
    void aTokenIsWellFormedOrNot() throws Exception {
        final String token = Provenance.sign(statement(), key().getPrivate());
        assertTrue(Provenance.wellFormed(token));
        assertFalse(Provenance.wellFormed(null));
        assertFalse(Provenance.wellFormed("short"));
        assertFalse(Provenance.wellFormed("x".repeat(Provenance.MAX_TOKEN + 1)));
        assertFalse(Provenance.wellFormed(token.replace('.', '!')));
        refused(() -> Provenance.split("garbage"), "unreadable token");
        refused(() -> Provenance.split("AAAAAAAAA." + "A".repeat(86)), "unreadable token");
        assertNull(Provenance.claimedKeyId("garbage"));
        assertNull(Provenance.claimedKeyId(Base64.getUrlEncoder().withoutPadding().encodeToString("not a statement at all".getBytes(StandardCharsets.UTF_8)) + "." + "A".repeat(86)));
        assertEquals("bup-2026", Provenance.claimedKeyId(token));
    }

    @Test
    void aTokenIsVerifiedWithTheRightKeyOnly() throws Exception {
        final KeyPair signer = key();
        final String token = Provenance.sign(statement(), signer.getPrivate());
        final Provenance.Signed signed = Provenance.verify(token, signer.getPublic());
        assertEquals(statement(), signed.statement());
        final KeyPair stranger = key();
        refused(() -> Provenance.verify(token, stranger.getPublic()), "invalid signature");
        final String tampered = token.substring(0, 20) + (token.charAt(20) == 'A' ? 'B' : 'A') + token.substring(21);
        final PublicKey signerKey = signer.getPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify(tampered, signerKey));
        final KeyPair rsa = KeyPairGenerator.getInstance("RSA").generateKeyPair();
        refused(() -> Provenance.verify(token, rsa.getPublic()), "invalid signature");
        refused(() -> Provenance.verify("garbage", signer.getPublic()), "unreadable token");
    }

    @Test
    void signingNeedsAnEd25519Key() throws Exception {
        final KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        final ProvenanceStatement statement = statement();
        final PrivateKey rsaKey = rsa.generateKeyPair().getPrivate();
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> Provenance.sign(statement, rsaKey));
        assertTrue(e.getMessage().contains("Ed25519 key expected"), e.getMessage());
    }

    @Test
    void aSignedValueComparesByContent() throws Exception {
        final KeyPair k = key();
        final String token = Provenance.sign(statement(), k.getPrivate());
        final Provenance.Signed a = Provenance.verify(token, k.getPublic());
        final Provenance.Signed b = Provenance.verify(token, k.getPublic());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Provenance.Signed(statement(), new byte[]{1}, a.signature()));
        assertNotEquals(a, new Provenance.Signed(statement(), a.statementBytes(), new byte[]{1}));
        assertNotEquals(a, new Provenance.Signed(new ProvenanceStatement("other", Map.of("amd64", A), null, "k", AT), a.statementBytes(), a.signature()));
        final Object other = "text";
        assertNotEquals(a, other);
        assertTrue(a.toString().startsWith("Signed[statement="));
    }

    // --- what the statement covers ---

    @Test
    void theStatementMustCoverTheApplicationsDeliverables() {
        final ProvenanceStatement s = new ProvenanceStatement("shop", Map.of("amd64", A, "arm64", B), null, "k", AT);
        assertNull(Provenance.mismatch(s, "shop", Map.of("amd64", "u#sha256=" + A), null), "the statement may list more architectures");
        assertTrue(Provenance.mismatch(s, "other", Map.of("amd64", "u#sha256=" + A), null).contains("another application"));
        assertTrue(Provenance.mismatch(s, "shop", Map.of("amd64", "u"), null).contains("without #sha256="));
        assertTrue(Provenance.mismatch(s, "shop", Map.of("amd64", "u#sha256=" + B), null).contains("not covered"));
        assertTrue(Provenance.mismatch(s, "shop", Map.of("riscv", "u#sha256=" + A), null).contains("not covered"));
        final ProvenanceStatement forImage = new ProvenanceStatement("shop", null, "sha256:" + A, "k", AT);
        assertNull(Provenance.mismatch(forImage, "shop", null, "r/shop@sha256:" + A));
        assertNull(Provenance.mismatch(forImage, "shop", Map.of(), " r/shop@sha256:" + A + " "));
        assertTrue(Provenance.mismatch(forImage, "shop", null, "r/shop:1").contains("without digest"));
        assertTrue(Provenance.mismatch(forImage, "shop", null, null).contains("without digest"));
        assertTrue(Provenance.mismatch(forImage, "shop", null, "r/shop@sha256:" + B).contains("not covered"));
    }

    @Test
    void referencesAreReadForTheirDigest() {
        assertNull(Provenance.artifactSha256(null));
        assertNull(Provenance.artifactSha256("https://x/y"));
        assertNull(Provenance.artifactSha256("https://x/y#sha256=tooshort"));
        assertEquals(A, Provenance.artifactSha256("https://x/y#sha256=" + A));
        assertEquals(A, Provenance.artifactSha256("https://x/y#sha256=" + A.toUpperCase()));
        assertNull(Provenance.imageDigest(null));
        assertNull(Provenance.imageDigest("r/shop:1"));
        assertEquals("sha256:" + A, Provenance.imageDigest("r/shop@sha256:" + A));
        assertNull(Provenance.imageDigest("r/shop@sha256:" + A.toUpperCase()));
    }

    @Test
    void twoSpecificationsRunTheSameBytesOrNot() {
        final Map<String, String> one = Map.of("amd64", "https://a/x#sha256=" + A, "arm64", "https://a/y#sha256=" + B);
        final Map<String, String> moved = Map.of("amd64", "https://mirror/x#sha256=" + A, "arm64", "https://mirror/y#sha256=" + B);
        assertTrue(Provenance.sameDeliverable(one, null, moved, null), "the URLs may differ");
        assertFalse(Provenance.sameDeliverable(one, null, Map.of("amd64", "u#sha256=" + A), null), "other architectures");
        assertFalse(Provenance.sameDeliverable(one, null, Map.of("amd64", "u#sha256=" + A, "arm64", "u#sha256=" + A), null), "other digest");
        assertFalse(Provenance.sameDeliverable(Map.of("amd64", "u"), null, Map.of("amd64", "u"), null), "no digest to compare");
        assertFalse(Provenance.sameDeliverable(one, null, null, "image"), "executables against an image");
        assertFalse(Provenance.sameDeliverable(null, "image", one, null));
        assertTrue(Provenance.sameDeliverable(null, " r/shop@sha256:" + A, Map.of(), "r/shop@sha256:" + A + " "));
        assertFalse(Provenance.sameDeliverable(null, "r/a", null, "r/b"));
        assertTrue(Provenance.sameDeliverable(null, null, null, null));
        assertFalse(Provenance.sameDeliverable(Map.of(), "r/a", Map.of(), null));
    }

    // --- keys ---

    @Test
    void keysAreReadFromPemOrBase64AndNeverEchoed() throws Exception {
        final KeyPair k = key();
        final String b64 = Provenance.publicKeyBase64(k.getPublic());
        assertEquals(k.getPublic(), Provenance.publicKey(b64));
        assertEquals(k.getPublic(), Provenance.publicKey("-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n"));
        refused(() -> Provenance.publicKey("not a key"), "unreadable Ed25519 public key");
        refused(() -> Provenance.publicKey(null), "unreadable Ed25519 public key");
        refused(() -> Provenance.publicKey(Base64.getEncoder().encodeToString(new byte[10])), "unreadable Ed25519 public key");
        final String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(k.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        assertEquals(k.getPrivate(), Provenance.privateKey(pem));
        final IllegalArgumentException secret = assertThrows(IllegalArgumentException.class, () -> Provenance.privateKey("-----BEGIN PRIVATE KEY-----\nSECRET-CONTENT\n-----END PRIVATE KEY-----"));
        assertFalse(secret.getMessage().contains("SECRET"), secret.getMessage());
        refused(() -> Provenance.privateKey(null), "unreadable Ed25519 private key");
    }

    @Test
    void aPrivateKeyFileIsReadAndAnUnreadableOneIsNamedByPathOnly() throws Exception {
        final KeyPair k = key();
        final Path good = Files.writeString(dir.resolve("good.pem"), "-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(k.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n");
        assertEquals(k.getPrivate(), Provenance.readPrivateKey(good));
        final Path bad = Files.writeString(dir.resolve("bad.pem"), "SECRET-CONTENT");
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Provenance.readPrivateKey(bad));
        assertTrue(e.getMessage().contains(bad.toString()), e.getMessage());
        assertFalse(e.getMessage().contains("SECRET"), e.getMessage());
        assertThrows(java.io.IOException.class, () -> Provenance.readPrivateKey(dir.resolve("absent.pem")));
    }
}
