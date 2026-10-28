package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The per-file signature in the gzip header: claims that cannot be read, a header that cannot be split, signing refusals, and every reason a verification fails. */
class GzipSignatureGapsTest {
    static final Instant NOW = Instant.parse("2026-10-08T00:00:00Z");
    static final String DIGEST = "a".repeat(64);
    static final String GOOD = "gluonify-file 1\nname=gluonify-strange\narch=arm64\nversion=1.2.3\nsequence=7\nexpires=2026-10-09T00:00:00Z\ndigest=" + DIGEST + "\n";

    @TempDir
    Path dir;

    static KeyPair key() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    /** A gzip with the given FEXTRA bytes (null: no FEXTRA) in front of a deflate stream of {@code content}. */
    Path gz(final byte[] extra, final byte[] content) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{0x1f, (byte) 0x8b, 8, (byte) (extra == null ? 0 : 4), 0, 0, 0, 0, 0, 3});
        if (extra != null) {
            out.write(extra.length & 0xff);
            out.write(extra.length >>> 8);
            out.writeBytes(extra);
        }
        final byte[] body = GzipGapsTest.gzip(8, 0, new byte[0], content, null);
        out.write(body, 10, body.length - 10);
        final Path p = dir.resolve("f-" + System.nanoTime() + ".gz");
        Files.write(p, out.toByteArray());
        return p;
    }

    Path plain() throws Exception {
        return gz(null, "native binary".repeat(100).getBytes(StandardCharsets.UTF_8));
    }

    static byte[] subfield(final char a, final char b, final byte[] data) {
        final byte[] sf = new byte[4 + data.length];
        sf[0] = (byte) a;
        sf[1] = (byte) b;
        sf[2] = (byte) (data.length & 0xff);
        sf[3] = (byte) (data.length >>> 8);
        System.arraycopy(data, 0, sf, 4, data.length);
        return sf;
    }

    Path signed(final Path unsigned, final KeyPair k) throws Exception {
        final Path out = dir.resolve("s-" + System.nanoTime() + ".gz");
        GzipSignature.sign(unsigned, out, new GzipSignature.Signing("gluonify-strange", "arm64", "1.2.3", 7, NOW.plusSeconds(86_400)), k.getPrivate());
        return out;
    }

    static String refusal(final org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ReleaseManifest.Invalid.class, call).getMessage();
    }

    // --- claims ---

    @Test
    void goodClaimsAreReadAndWrittenBackExactly() {
        final GzipSignature.Claims c = GzipSignature.Claims.parse(GOOD.getBytes(StandardCharsets.UTF_8));
        assertEquals("gluonify-strange", c.name());
        assertEquals(7, c.sequence());
        assertArrayEquals(GOOD.getBytes(StandardCharsets.UTF_8), c.bytes());
    }

    @Test
    void claimsThatCannotBeReadAreRefusedWithTheirReason() {
        final String[] bad = {
            GOOD.replace("gluonify-file 1", "gluonify-file 2"),
            GOOD + "extra=1\n",
            GOOD.substring(0, GOOD.length() - 1),
            GOOD.replace("name=", "nom="),
            GOOD.replace("name=gluonify-strange", "name=-bad name"),
            GOOD.replace("arch=arm64", "arch=sparc"),
            GOOD.replace("version=1.2.3", "version=latest"),
            GOOD.replace("digest=" + DIGEST, "digest=XYZ"),
            GOOD.replace("expires=2026-10-09T00:00:00Z", "expires=tomorrow"),
            GOOD.replace("sequence=7", "sequence=seven"),
            "",
        };
        for (final String claims : bad) {
            final String message = refusal(() -> GzipSignature.Claims.parse(claims.getBytes(StandardCharsets.UTF_8)));
            assertTrue(message.startsWith("file signature:"), claims + " -> " + message);
        }
        assertTrue(refusal(() -> GzipSignature.Claims.parse(GOOD.replace("arch=arm64", "arch=sparc").getBytes(StandardCharsets.UTF_8))).contains("invalid claims"));
        assertTrue(refusal(() -> GzipSignature.Claims.parse(GOOD.replace("version=", "ver=").getBytes(StandardCharsets.UTF_8))).contains("\"version\" expected"));
    }

    @Test
    void aSignedValueComparesByContent() {
        final GzipSignature.Signed a = new GzipSignature.Signed(new byte[]{1, 2}, new byte[]{3});
        assertEquals(a, new GzipSignature.Signed(new byte[]{1, 2}, new byte[]{3}));
        assertEquals(a.hashCode(), new GzipSignature.Signed(new byte[]{1, 2}, new byte[]{3}).hashCode());
        assertNotEquals(a, new GzipSignature.Signed(new byte[]{1, 9}, new byte[]{3}));
        assertNotEquals(a, new GzipSignature.Signed(new byte[]{1, 2}, new byte[]{9}));
        final Object other = "text";
        assertNotEquals(a, other);
        assertEquals("Signed[claimsBytes=2 bytes, signature=1 bytes]", a.toString());
    }

    // --- the header ---

    @Test
    void aHeaderThatCannotBeReadIsRefused() throws Exception {
        final Path notGzip = Files.write(dir.resolve("a.bin"), new byte[]{1, 2, 3});
        assertTrue(refusal(() -> GzipSignature.read(notGzip)).contains("not a gzip file"));
        final Path badMagic = Files.write(dir.resolve("b.bin"), new byte[]{0x1f, 0, 8, 0, 0, 0, 0, 0, 0, 0, 0});
        assertTrue(refusal(() -> GzipSignature.read(badMagic)).contains("not a gzip file"));
        final byte[] withCrc = Files.readAllBytes(plain());
        withCrc[3] = 2;
        final Path crc = Files.write(dir.resolve("c.gz"), withCrc);
        assertTrue(refusal(() -> GzipSignature.read(crc)).contains("FHCRC not supported"));
        final byte[] noLength = Arrays.copyOf(withCrc, 10);
        noLength[3] = 4;
        final Path cut = Files.write(dir.resolve("d.gz"), noLength);
        assertTrue(refusal(() -> GzipSignature.read(cut)).contains("gzip truncated"));
        final byte[] shortExtra = Arrays.copyOf(noLength, 13);
        shortExtra[10] = 9;
        final Path cut2 = Files.write(dir.resolve("e.gz"), shortExtra);
        assertTrue(refusal(() -> GzipSignature.read(cut2)).contains("gzip truncated"));
    }

    @Test
    void malformedExtraFieldsAreRefused() {
        assertTrue(refusal(() -> GzipSignature.read(gz(new byte[]{'A', 'B'}, new byte[]{1}))).contains("malformed FEXTRA"));
        final byte[] outOfRange = {'A', 'B', 9, 0, 1};
        assertTrue(refusal(() -> GzipSignature.read(gz(outOfRange, new byte[]{1}))).contains("subfield out of range"));
        final byte[] two = new byte[10];
        System.arraycopy(subfield('G', 'L', new byte[]{1}), 0, two, 0, 5);
        System.arraycopy(subfield('G', 'L', new byte[]{2}), 0, two, 5, 5);
        assertTrue(refusal(() -> GzipSignature.read(gz(two, new byte[]{1}))).contains("several file signatures"));
    }

    @Test
    void anotherSubfieldIsNotASignatureAndSurvivesSigning() throws Exception {
        final KeyPair k = key();
        final Path withOther = gz(subfield('A', 'B', new byte[]{9, 9}), "content".repeat(50).getBytes(StandardCharsets.UTF_8));
        assertNull(GzipSignature.read(withOther));
        final Path s = signed(withOther, k);
        final GzipSignature.Claims c = GzipSignature.verify(s, List.of(k.getPublic()), NOW, null);
        assertEquals("1.2.3", c.version());
        assertEquals(GzipSignature.unsignedDigest(withOther), GzipSignature.unsignedDigest(s), "the unsigned form of the signed file is the original one");
    }

    @Test
    void aSignatureSubfieldThatIsMalformedIsRefused() throws Exception {
        for (final byte[] data : new byte[][]{new byte[10], concat(new byte[]{2, 0, 0}, new byte[64]), concat(new byte[]{1, 5, 0}, new byte[10])}) {
            final Path p = gz(subfield('G', 'L', data), new byte[]{1});
            assertTrue(refusal(() -> GzipSignature.read(p)).startsWith("file signature:"), Arrays.toString(Arrays.copyOf(data, 4)));
        }
    }

    static byte[] concat(final byte[] a, final byte[] b) {
        final byte[] out = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    // --- signing ---

    @Test
    void signingRefusesAFileThatAlreadyCarriesASignatureAndOversizedFields() throws Exception {
        final KeyPair k = key();
        final Path s = signed(plain(), k);
        assertTrue(refusal(() -> signed(s, k)).contains("already carries a signature"));
        final Path huge = dir.resolve("huge.gz");
        final GzipSignature.Signing longName = new GzipSignature.Signing("n".repeat(61_000), "arm64", "1.2.3", 1, NOW);
        assertTrue(refusal(() -> GzipSignature.sign(plain(), huge, longName, k.getPrivate())).contains("claims too long"));
        final Path bigExtra = gz(subfield('A', 'B', new byte[65_400]), new byte[]{1});
        assertTrue(refusal(() -> GzipSignature.sign(bigExtra, dir.resolve("big-out.gz"), new GzipSignature.Signing("gluonify-strange", "arm64", "1.2.3", 1, NOW), k.getPrivate())).contains("FEXTRA too long"));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedSigningFormStillSigns() throws Exception {
        final KeyPair k = key();
        final Path out = dir.resolve("old-form.gz");
        GzipSignature.sign(plain(), out, "gluonify-strange", "arm64", "1.2.3", 7, NOW.plusSeconds(60), k.getPrivate());
        assertEquals(7, GzipSignature.verify(out, List.of(k.getPublic()), NOW, null).sequence());
    }

    // --- verification ---

    @Test
    void everyReasonAFileIsNotAccepted() throws Exception {
        final KeyPair k = key();
        final KeyPair other = key();
        final Path unsigned = plain();
        final Path s = signed(unsigned, k);
        assertTrue(refusal(() -> GzipSignature.verify(unsigned, List.of(k.getPublic()), NOW, null)).contains("carries no signature"));
        assertTrue(refusal(() -> GzipSignature.verify(s, List.of(other.getPublic()), NOW, null)).contains("no trusted key validates it"));
        assertTrue(refusal(() -> GzipSignature.verify(s, List.of(), NOW, null)).contains("no trusted key validates it"));
        assertEquals("gluonify-strange", GzipSignature.verify(s, List.of(other.getPublic(), k.getPublic()), NOW, null).name(), "the second key is the right one");
        assertTrue(refusal(() -> GzipSignature.verify(s, List.of(k.getPublic()), NOW.plusSeconds(86_401), null)).contains("expired"));
        assertEquals("gluonify-strange", GzipSignature.verify(s, List.of(k.getPublic()), NOW.plusSeconds(86_400), null).name(), "the expiry instant itself is still valid");

        final byte[] modified = Files.readAllBytes(s);
        modified[modified.length - 12] ^= 1;
        final Path changed = Files.write(dir.resolve("changed.gz"), modified);
        assertTrue(refusal(() -> GzipSignature.verify(changed, List.of(k.getPublic()), NOW, null)).contains("does not match the content"));
    }

    @Test
    void theExpectedComponentArchitectureVersionAndSequenceAreChecked() throws Exception {
        final KeyPair k = key();
        final Path s = signed(plain(), k);
        final List<java.security.PublicKey> keys = List.of(k.getPublic());
        assertTrue(refusal(() -> GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected("other", null, null, null))).contains("component gluonify-strange instead of other"));
        assertTrue(refusal(() -> GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected(null, "amd64", null, null))).contains("architecture arm64 instead of amd64"));
        assertTrue(refusal(() -> GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected(null, null, "9.9.9", null))).contains("version 1.2.3 instead of 9.9.9"));
        assertTrue(refusal(() -> GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected(null, null, null, 8L))).contains("sequence 7 instead of 8"));
        assertEquals(7, GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected("gluonify-strange", "arm64", "1.2.3", 7L)).sequence());
        assertEquals(7, GzipSignature.verify(s, keys, NOW, new GzipSignature.Expected(null, null, null, null)).sequence());
    }

    @Test
    void theSignedDigestIsThatOfTheUnsignedFormWhateverTheCodeReadingPath() throws Exception {
        final Path u = plain();
        assertEquals(ReleaseVerifier.sha256(u), GzipSignature.unsignedDigest(u));
        assertEquals('G', GzipSignature.SI1);
        assertEquals('L', GzipSignature.SI2);
        assertEquals(1, GzipSignature.FORMAT);
    }
}
