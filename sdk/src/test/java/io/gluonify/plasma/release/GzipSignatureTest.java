package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GzipSignatureTest {
    @TempDir Path dir;
    static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");

    static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    Path plain(byte[] content) throws Exception {
        var b = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(b)) { g.write(content); }
        Path p = dir.resolve("f-" + System.nanoTime() + ".gz");
        Files.write(p, b.toByteArray());
        return p;
    }

    Path signed(Path unsigned, KeyPair k) throws Exception {
        Path out = dir.resolve("s-" + System.nanoTime() + ".gz");
        GzipSignature.sign(unsigned, out, "gluonify-strange", "arm64", "1.2.3", 7, NOW.plusSeconds(86400), k.getPrivate());
        return out;
    }

    static final GzipSignature.Expected EXPECT = new GzipSignature.Expected("gluonify-strange", "arm64", "1.2.3", 7L);

    @Test void aSignedFileVerifiesAloneAndDecompressesNormally() throws Exception {
        KeyPair k = key();
        byte[] content = "binaire natif de strange".repeat(1000).getBytes();
        Path u = plain(content), s = signed(u, k);
        var c = GzipSignature.verify(s, List.of(k.getPublic()), NOW, EXPECT);
        assertEquals("gluonify-strange", c.name());
        assertEquals(ReleaseVerifier.sha256(u), c.digest(), "the signed fingerprint is that of the unsigned form");
        assertEquals(ReleaseVerifier.sha256(u), GzipSignature.unsignedDigest(s));
        var out = new ByteArrayOutputStream();
        Gzip.gunzip(Files.newInputStream(s), Files.size(s), 1 << 20, out);
        assertArrayEquals(content, out.toByteArray(), "a signed gzip decompresses as before");
        assertNull(GzipSignature.read(u));
        assertTrue(Files.size(s) > Files.size(u));
    }

    @Test void anyModifiedByteIsRefused() throws Exception {
        KeyPair k = key();
        Path s = signed(plain("contenu".repeat(500).getBytes()), k);
        byte[] b = Files.readAllBytes(s);
        for (int i : new int[] {b.length - 12, b.length - 1, b.length / 2}) { // flux deflate, pied de page
            byte[] t = b.clone();
            t[i] ^= 1;
            Path f = dir.resolve("t" + i + ".gz");
            Files.write(f, t);
            assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(f, List.of(k.getPublic()), NOW, EXPECT), "octet " + i);
        }
        // modified claims: the signature no longer validates them
        byte[] t = b.clone();
        int at = new String(t, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("sequence=7");
        t[at + 9] = '8';
        Path f = dir.resolve("claims.gz");
        Files.write(f, t);
        assertTrue(assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(f, List.of(k.getPublic()), NOW, null)).getMessage().contains("invalid"));
    }

    @Test void unknownKeyExpiredAndUnexpectedClaims() throws Exception {
        KeyPair k = key(), other = key();
        Path s = signed(plain("x".repeat(100).getBytes()), k);
        assertTrue(assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(s, List.of(other.getPublic()), NOW, EXPECT)).getMessage().contains("invalid"));
        assertEquals("gluonify-strange", GzipSignature.verify(s, List.of(other.getPublic(), k.getPublic()), NOW, EXPECT).name(), "any one of the keys is enough (rotation)");
        assertTrue(assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(s, List.of(k.getPublic()), NOW.plusSeconds(86400 * 2), EXPECT)).getMessage().contains("expired"));
        for (var bad : List.of(new GzipSignature.Expected("gluonify-higgs", null, null, null), new GzipSignature.Expected(null, "amd64", null, null),
                new GzipSignature.Expected(null, null, "1.2.4", null), new GzipSignature.Expected(null, null, null, 8L)))
            assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(s, List.of(k.getPublic()), NOW, bad), bad.toString());
        assertTrue(assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(plain("y".getBytes()), List.of(k.getPublic()), NOW, EXPECT)).getMessage().contains("no signature"));
    }

    @Test void theOtherSubfieldsAndTheNameAreKept() throws Exception {
        KeyPair k = key();
        // gzip written by hand with FEXTRA (subfield "AB") and FNAME
        byte[] raw = plain("données".repeat(50).getBytes()).toFile().length() > 0 ? Files.readAllBytes(plain("données".repeat(50).getBytes())) : null;
        var o = new ByteArrayOutputStream();
        byte[] sub = {'A', 'B', 3, 0, 1, 2, 3};
        o.write(new byte[] {raw[0], raw[1], raw[2], 4 | 8, 0, 0, 0, 0, raw[8], raw[9]});
        o.write(sub.length); o.write(0); o.write(sub);
        o.write("nom.bin\0".getBytes());
        o.write(raw, 10, raw.length - 10);
        Path u = dir.resolve("custom.gz");
        Files.write(u, o.toByteArray());
        Path s = signed(u, k);
        GzipSignature.verify(s, List.of(k.getPublic()), NOW, EXPECT);
        assertEquals(ReleaseVerifier.sha256(u), GzipSignature.unsignedDigest(s), "the unsigned form is identical to the original, AB subfield and name included");
        // changing the name (outside the subfield) breaks the signature: it is covered
        byte[] b = Files.readAllBytes(s);
        int at = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1).indexOf("nom.bin");
        b[at] = 'x';
        Path t = dir.resolve("renamed.gz");
        Files.write(t, b);
        assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.verify(t, List.of(k.getPublic()), NOW, EXPECT));
        assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.sign(s, dir.resolve("again.gz"), "gluonify-strange", "arm64", "1.2.3", 7, NOW, k.getPrivate()), "already signed");
    }

    @Test void malformedGzipOrWithFhcrcRefused() throws Exception {
        KeyPair k = key();
        Path s = signed(plain("abc".repeat(100).getBytes()), k);
        byte[] b = Files.readAllBytes(s);
        byte[] fh = b.clone();
        fh[3] |= 2;
        Path f1 = dir.resolve("fhcrc.gz");
        Files.write(f1, fh);
        assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.read(f1));
        byte[] trunc = java.util.Arrays.copyOf(b, 20);
        Path f2 = dir.resolve("trunc.gz");
        Files.write(f2, trunc);
        assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.read(f2));
        Path f3 = dir.resolve("notgz");
        Files.write(f3, "hello".getBytes());
        assertThrows(ReleaseManifest.Invalid.class, () -> GzipSignature.read(f3));
    }

    @Test void theManifestAcceptsUpTo1024Artifacts() {
        var list = new java.util.ArrayList<ReleaseManifest.Artifact>();
        for (int i = 0; i < 1024; i++) list.add(new ReleaseManifest.Artifact("comp-" + i, "amd64", "comp-" + i + "-linux-amd64.gz", "a".repeat(64), 10));
        new ReleaseManifest(1, "1.0.0", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", list).validate(ReleaseManifest.SAFE_NAME.asMatchPredicate());
        list.add(new ReleaseManifest.Artifact("comp-x", "amd64", "comp-x-linux-amd64.gz", "a".repeat(64), 10));
        assertThrows(ReleaseManifest.Invalid.class, () -> new ReleaseManifest(1, "1.0.0", 1, "2026-01-01T00:00:00Z", "2027-01-01T00:00:00Z", list).validate(ReleaseManifest.SAFE_NAME.asMatchPredicate()));
    }

    /** File signed by bin/gzip-sign.py (Python + openssl): the Java SDK verifies it, and conversely the format is the same on both sides. */
    @Test void aFileSignedByThePythonToolVerifiesInJava() throws Exception {
        Path gz = Path.of("src/test/resources/gzip-signature/gluonify-strange-linux-arm64.gz");
        var pub = ReleaseVerifier.publicKey(Files.readString(Path.of("src/test/resources/gzip-signature/release.pub")));
        var c = GzipSignature.verify(gz, List.of(pub), NOW, new GzipSignature.Expected("gluonify-strange", "arm64", "1.2.3", 7L));
        assertEquals("2099-01-01T00:00:00Z", c.expires());
        var out = new ByteArrayOutputStream();
        Gzip.gunzip(Files.newInputStream(gz), Files.size(gz), 1 << 20, out);
        assertTrue(out.toString().startsWith("strange binary arm64"));
    }
}
