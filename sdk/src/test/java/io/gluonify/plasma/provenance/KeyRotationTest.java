package io.gluonify.plasma.provenance;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * Rotation statement of a provenance key: canonical bytes, strict parsing, cross-signature and proof of possession.
 *
 * @author David CABILLIC
 */
class KeyRotationTest {

    private static final Instant AT = Instant.parse("2026-10-29T12:00:00.750Z");

    private static KeyPair pair() throws NoSuchAlgorithmException {

        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private static KeyRotation rotation(final KeyPair next) {

        return new KeyRotation("bup", "bup-old", "bup-new", Provenance.publicKeyBase64(next.getPublic()), AT);
    }

    private static String b64(final byte[] b) {

        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    @Test
    void canonicalBytesCarryAThirtyDayOverlap() throws Exception {

        KeyPair next = pair();
        KeyRotation r = rotation(next);
        String expected = "gluonify-key-rotation 1\nkind bup\nold bup-old\nnew bup-new\nnew-key " + Provenance.publicKeyBase64(next.getPublic())
                + "\nsigned-at 2026-10-29T12:00:00Z\nretire-old-after 2026-11-28T12:00:00Z\n";
        assertEquals(expected, new String(r.canonicalBytes(), StandardCharsets.UTF_8));
        assertEquals(Instant.parse("2026-11-28T12:00:00Z"), r.retireOldAfter());
        assertEquals(r, KeyRotation.parse(r.canonicalBytes()));
    }

    @Test
    void signedByBothKeysVerifiesUnderTheOldOne() throws Exception {

        KeyPair old = pair();
        KeyPair next = pair();
        String token = KeyRotation.sign(rotation(next), old.getPrivate(), next.getPrivate());
        assertTrue(KeyRotation.wellFormed(token));
        assertEquals("bup-old", KeyRotation.claimedOldKeyId(token));
        KeyRotation r = KeyRotation.verify(token, old.getPublic());
        assertEquals("bup-new", r.newKeyId());
        assertArrayEquals(next.getPublic().getEncoded(), r.newPublicKey().getEncoded());
    }

    @Test
    void anotherOldKeyIsRefused() throws Exception {

        KeyPair next = pair();
        String token = KeyRotation.sign(rotation(next), pair().getPrivate(), next.getPrivate());
        java.security.PublicKey stranger = pair().getPublic();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KeyRotation.verify(token, stranger));
        assertTrue(e.getMessage().contains("old key"));
    }

    @Test
    void aProofOfPossessionByAnotherKeyIsRefused() throws Exception {

        KeyPair old = pair();
        KeyPair next = pair();
        KeyRotation r = rotation(next);
        java.security.PrivateKey oldPrivate = old.getPrivate();
        java.security.PrivateKey otherPrivate = pair().getPrivate();
        assertThrows(IllegalArgumentException.class, () -> KeyRotation.sign(r, oldPrivate, otherPrivate));
        String good = KeyRotation.sign(r, old.getPrivate(), next.getPrivate());
        java.security.Signature other = java.security.Signature.getInstance("Ed25519");
        other.initSign(pair().getPrivate());
        other.update(r.canonicalBytes());
        String forged = good.substring(0, good.lastIndexOf('.') + 1) + b64(other.sign());
        assertNotEquals(good, forged);
        java.security.PublicKey oldPublic = old.getPublic();
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> KeyRotation.verify(forged, oldPublic));
        assertTrue(e.getMessage().contains("new key"));
    }

    @Test
    void nonCanonicalOrOutOfRangeBytesAreRefused() throws Exception {

        String key = Provenance.publicKeyBase64(pair().getPublic());
        String base = "gluonify-key-rotation 1\nkind bup\nold bup-old\nnew bup-new\nnew-key " + key + "\nsigned-at 2026-10-29T12:00:00Z\n";
        for (String bad : new String[] {base + "retire-old-after 2027-10-29T12:00:00Z\n", base + "retire-old-after 2026-11-28T12:00:00Z",
                base.replace("kind bup", "kind  bup"), base.replace("signed-at 2026", "signed-at x2026")}) {
            byte[] bytes = bad.getBytes(StandardCharsets.UTF_8);
            assertThrows(IllegalArgumentException.class, () -> KeyRotation.parse(bytes), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> KeyRotation.parse(new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> KeyRotation.parse(null));
    }

    @Test
    void invalidFieldsAreRefused() throws Exception {

        String key = Provenance.publicKeyBase64(pair().getPublic());
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("Bup", "a", "b", key, AT));
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "A", "b", key, AT));
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "a", "B", key, AT));
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "a", "a", key, AT));
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "a", "b", "short", AT));
        String notAKey = "A".repeat(60);
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "a", "b", notAKey, AT));
        assertThrows(IllegalArgumentException.class, () -> new KeyRotation("bup", "a", "b", key, null));
    }

    @Test
    void unreadableTokensAreRefused() throws Exception {

        KeyPair old = pair();
        String sig = b64(new byte[64]);
        assertFalse(KeyRotation.wellFormed(null));
        assertFalse(KeyRotation.wellFormed("abc"));
        assertNull(KeyRotation.claimedOldKeyId("abc"));
        assertNull(KeyRotation.claimedOldKeyId(b64("not a rotation".getBytes(StandardCharsets.UTF_8)) + "." + sig + "." + sig));
        java.security.PublicKey oldPublic = old.getPublic();
        String shortStatement = "a".repeat(9) + "." + sig + "." + sig;
        assertThrows(IllegalArgumentException.class, () -> KeyRotation.verify("abc", oldPublic));
        assertThrows(IllegalArgumentException.class, () -> KeyRotation.verify(shortStatement, oldPublic));
    }

    @Test
    void publicKeyOfAPrivateKeyMatchesItsPair() throws Exception {

        KeyPair k = pair();
        assertArrayEquals(k.getPublic().getEncoded(), Provenance.publicKeyOf(k.getPrivate()).getEncoded());
        assertArrayEquals(ProvenanceTest.vectorPublic().getEncoded(), Provenance.publicKeyOf(ProvenanceTest.vectorPrivate()).getEncoded());
    }

    @Test
    void derivedKeyIdIsStableAndValid() throws Exception {

        KeyPair k = pair();
        String id = Provenance.derivedKeyId("bup", k.getPublic());
        assertTrue(id.matches("bup-[0-9a-f]{12}"));
        assertEquals(id, Provenance.derivedKeyId("bup", Provenance.publicKey(Provenance.publicKeyBase64(k.getPublic()))));
        assertTrue(ProvenanceStatement.KEY_ID.matcher(id).matches());
        java.security.PublicKey pub = k.getPublic();
        assertThrows(IllegalArgumentException.class, () -> Provenance.derivedKeyId("Bup", pub));
        assertThrows(IllegalArgumentException.class, () -> Provenance.derivedKeyId(null, pub));
    }
}
