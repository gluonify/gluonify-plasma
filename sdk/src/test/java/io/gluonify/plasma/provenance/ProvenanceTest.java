package io.gluonify.plasma.provenance;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.AppSpec;
import io.gluonify.plasma.DeployRequest;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Canonical statement, token, signature and verification; fixed vectors (key of test 1 of RFC 8032, Ed25519 is deterministic). */
class ProvenanceTest {
    static final String SEED = "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60";
    static final String PUB = "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a";
    static final String SHA_A = "a".repeat(64), SHA_B = "b".repeat(64);
    static final Instant AT = Instant.parse("2026-10-11T20:00:00Z");

    static PrivateKey vectorPrivate() {
        String der = "302e020100300506032b657004220420" + SEED;
        return Provenance.privateKey("-----BEGIN PRIVATE KEY-----\n" + Base64.getEncoder().encodeToString(HexFormat.of().parseHex(der)) + "\n-----END PRIVATE KEY-----\n");
    }

    static PublicKey vectorPublic() {
        return Provenance.publicKey(Base64.getEncoder().encodeToString(HexFormat.of().parseHex("302a300506032b6570032100" + PUB)));
    }

    static ProvenanceStatement statement() {
        return new ProvenanceStatement("shop", Map.of("arm64", SHA_B, "amd64", SHA_A), null, "bup-2026", AT);
    }

    @Test void canonicalBytes() {
        String expected = "gluonify-provenance 1\napp shop\nartifact amd64 " + SHA_A + "\nartifact arm64 " + SHA_B + "\nkey bup-2026\nsigned-at 2026-10-11T20:00:00Z\n";
        assertEquals(expected, new String(statement().canonicalBytes(), StandardCharsets.UTF_8));
        String image = "gluonify-provenance 1\napp shop\nimage sha256:" + SHA_A + "\nkey release-1\nsigned-at 2026-10-11T20:00:00Z\n";
        assertEquals(image, new String(new ProvenanceStatement("shop", null, "sha256:" + SHA_A, "release-1", AT.plusMillis(999)).canonicalBytes(), StandardCharsets.UTF_8), "secondes seulement");
    }

    @Test void signatureVector() {
        String token = Provenance.sign(statement(), vectorPrivate());
        assertEquals(VECTOR, token, "Ed25519 is deterministic: same key, same bytes, same token");
        var v = Provenance.verify(token, vectorPublic());
        assertEquals(statement(), v.statement());
        assertEquals("bup-2026", Provenance.claimedKeyId(token));
    }

    @Test void signedTokensCompareByContent() {
        var a = Provenance.verify(VECTOR, vectorPublic());
        var b = Provenance.verify(VECTOR, vectorPublic());
        assertNotSame(a.signature(), b.signature());
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new Provenance.Signed(a.statement(), a.statementBytes(), new byte[64]));
        assertTrue(a.toString().startsWith("Signed[statement="));
    }

    /** Vector also checked with openssl pkeyutl -sign -rawin (same signature). */
    static final String VECTOR = "Z2x1b25pZnktcHJvdmVuYW5jZSAxCmFwcCBzaG9wCmFydGlmYWN0IGFtZDY0IGFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWEKYXJ0aWZhY3QgYXJtNjQgYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYmJiYgprZXkgYnVwLTIwMjYKc2lnbmVkLWF0IDIwMjYtMTAtMTFUMjA6MDA6MDBaCg._7kIBsZDPJQkTHA0QUa7Wi3IWPyNTsoWWTJtun8tgL_F9Vb-PovIZmhreB7wAo_DmEiTZhLCxZ4qJdQw8vMABA";

    @Test void falseSignatureRefused() throws Exception {
        String token = Provenance.sign(statement(), vectorPrivate());
        PublicKey other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair().getPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify(token, other), "other key");
        // modified statement (other fingerprint), original signature
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(new ProvenanceStatement("shop", Map.of("amd64", SHA_B, "arm64", SHA_B), null, "bup-2026", AT).canonicalBytes())
                + token.substring(token.indexOf('.'));
        final var input5 = vectorPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify(forged, input5), "forged fingerprint");
        // signature modified by one bit
        char[] c = token.toCharArray();
        int i = token.length() - 3;
        c[i] = c[i] == 'A' ? 'B' : 'A';
        final var input3 = new String(c);
        final var input4 = vectorPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify(input3, input4));
        final var input2 = vectorPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify("pas-un-jeton", input2));
        final var input = vectorPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.verify(null, input));
    }

    @Test void strictReading() {
        byte[] ok = statement().canonicalBytes();
        assertEquals(statement(), ProvenanceStatement.parse(ok));
        String s = new String(ok, StandardCharsets.UTF_8);
        for (String bad : new String[] {
                s.replace("artifact amd64 " + SHA_A + "\nartifact arm64 " + SHA_B, "artifact arm64 " + SHA_B + "\nartifact amd64 " + SHA_A), // ordre
                s.replace("app shop", "app  shop"), s.replace("gluonify-provenance 1", "gluonify-provenance 2"), s + "x\n", s.substring(0, s.length() - 1),
                s.replace("signed-at 2026-10-11T20:00:00Z", "signed-at 2026-10-11T20:00:00.5Z"), s.replace("key bup-2026\n", ""), s.replace("app shop", "app Shop"),
                s.replace(SHA_A, SHA_A.toUpperCase()), "{\"schema\":1}\n"}) {
            final var input = bad.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> ProvenanceStatement.parse(input), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceStatement("shop", Map.of(), null, "k", AT), "neither executable nor image");
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceStatement("shop", null, "sha256:abc", "k", AT));
        assertThrows(IllegalArgumentException.class, () -> new ProvenanceStatement("shop", null, "sha256:" + SHA_A, "K!", AT));
    }

    @Test void deliverablesCoverage() {
        var s = statement();
        Map<String, String> arts = Map.of("amd64", "https://b/x#sha256=" + SHA_A, "arm64", "file:///y#sha256=" + SHA_B.toUpperCase());
        assertNull(Provenance.mismatch(s, "shop", arts, null));
        assertNull(Provenance.mismatch(s, "shop", Map.of("amd64", "https://other/url#sha256=" + SHA_A), null), "subset of the architectures, free URL");
        assertNotNull(Provenance.mismatch(s, "shop2", arts, null), "autre application");
        assertNotNull(Provenance.mismatch(s, "shop", Map.of("amd64", "https://b/x#sha256=" + SHA_B), null), "autre empreinte");
        assertNotNull(Provenance.mismatch(s, "shop", Map.of("darwin-arm64", "https://b/x#sha256=" + SHA_A), null), "architecture non couverte");
        assertNotNull(Provenance.mismatch(s, "shop", Map.of("amd64", "https://b/x"), null), "sans empreinte");
        assertNotNull(Provenance.mismatch(s, "shop", null, "reg/shop@sha256:" + SHA_A), "executables statement, not an image");
        var img = new ProvenanceStatement("shop", null, "sha256:" + SHA_A, "bup-2026", AT);
        assertNull(Provenance.mismatch(img, "shop", null, "reg.example.org/shop@sha256:" + SHA_A));
        assertNotNull(Provenance.mismatch(img, "shop", null, "reg.example.org/shop:1.0"), "tag");
        assertNotNull(Provenance.mismatch(img, "shop", null, "reg.example.org/shop@sha256:" + SHA_B));
        assertNotNull(Provenance.mismatch(img, "shop", arts, null), "image statement, not executables");
    }

    @Test void sameDeliverableDetection() {
        var a = Map.of("amd64", "https://b/x#sha256=" + SHA_A);
        assertTrue(Provenance.sameDeliverable(a, null, Map.of("amd64", "https://c/y#sha256=" + SHA_A), null), "different URL, same bytes");
        assertFalse(Provenance.sameDeliverable(a, null, Map.of("amd64", "https://b/x#sha256=" + SHA_B), null));
        assertFalse(Provenance.sameDeliverable(a, null, Map.of("amd64", "https://b/x#sha256=" + SHA_A, "arm64", "https://b/x#sha256=" + SHA_A), null), "architecture added");
        assertFalse(Provenance.sameDeliverable(Map.of("amd64", "https://b/x"), null, Map.of("amd64", "https://b/x"), null), "without fingerprint: never the same");
        assertTrue(Provenance.sameDeliverable(null, "img:1", Map.of(), "img:1"));
        assertFalse(Provenance.sameDeliverable(null, "img:1", null, "img:2"));
        assertFalse(Provenance.sameDeliverable(a, null, null, "img:1"));
    }

    @Test void statementFromTheSpecification() {
        var s = ProvenanceStatement.of("shop", Map.of("amd64", "https://b/x#sha256=" + SHA_A), null, "bup-2026", AT);
        assertEquals(Map.of("amd64", SHA_A), s.artifacts());
        final var input = Map.of("amd64", "https://b/x");
        assertThrows(IllegalArgumentException.class, () -> ProvenanceStatement.of("shop", input, null, "k", AT));
        assertThrows(IllegalArgumentException.class, () -> ProvenanceStatement.of("shop", null, "reg/shop:1", "k", AT));
        assertEquals("sha256:" + SHA_A, ProvenanceStatement.of("shop", null, "reg/shop@sha256:" + SHA_A, "k", AT).imageDigest());
    }

    @Test void specificationField() {
        String token = Provenance.sign(statement(), vectorPrivate());
        AppSpec spec = new DeployRequest(null, null, null, null, null, Map.of("amd64", "https://b/x#sha256=" + SHA_A), null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, token).toSpec("shop", null);
        assertEquals(token, spec.provenance());
        assertEquals(token, spec.withOrganization("acme").provenance());
        assertEquals(token, spec.withDistributedQuotaMb(null).provenance());
        assertNull(spec.withProvenance(null).provenance());
        assertNull(new AppSpec("shop", "img:1", null, null, null, null).provenance(), "compatibility constructor");
        assertThrows(IllegalArgumentException.class, () -> spec.withProvenance("x.y"), "malformed token");
        assertNull(spec.withProvenance(" ").provenance());
    }
}
