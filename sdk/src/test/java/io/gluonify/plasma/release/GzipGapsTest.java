package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import org.junit.jupiter.api.Test;

/** The gzip reader of the releases: every field of a header it must skip, and every way a file can be damaged, truncated or hostile. */
class GzipGapsTest {
    static final byte[] TEXT = "hello gzip, a release file".repeat(20).getBytes(StandardCharsets.UTF_8);

    /** A gzip built by hand: the flags, the optional header parts and the footer are the test's to choose. */
    static byte[] gzip(final int method, final int flags, final byte[] afterFixed, final byte[] content, final byte[] footerOverride) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x1f);
        out.write(0x8b);
        out.write(method);
        out.write(flags);
        out.writeBytes(new byte[]{0, 0, 0, 0, 0, 3});
        out.writeBytes(afterFixed);
        final Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(content);
        deflater.finish();
        final byte[] buffer = new byte[content.length + 64];
        final int n = deflater.deflate(buffer);
        deflater.end();
        out.write(buffer, 0, n);
        if (footerOverride != null) {
            out.writeBytes(footerOverride);
        } else {
            out.writeBytes(footer(content));
        }
        return out.toByteArray();
    }

    static byte[] footer(final byte[] content) {
        final CRC32 crc = new CRC32();
        crc.update(content);
        final byte[] f = new byte[8];
        for (int i = 0; i < 4; i++) {
            f[i] = (byte) (crc.getValue() >> (8 * i));
            f[4 + i] = (byte) (content.length >> (8 * i));
        }
        return f;
    }

    static byte[] plain(final byte[] content) {
        return gzip(8, 0, new byte[0], content, null);
    }

    static String refusal(final byte[] gz) {
        return assertThrows(ReleaseManifest.Invalid.class, () -> Gzip.gunzip(new ByteArrayInputStream(gz), gz.length, Gzip.DEFAULT_MAX_BYTES, new ByteArrayOutputStream())).getMessage();
    }

    // --- names and limits ---

    @Test
    void onlyASingleCompressedFileIsGunzipped() {
        assertTrue(Gzip.isSingleFile("strange-linux-x86-64-v3.gz"));
        assertTrue(Gzip.isSingleFile("a.gz"));
        assertFalse(Gzip.isSingleFile(null));
        assertFalse(Gzip.isSingleFile(".gz"), "no name before the extension");
        assertFalse(Gzip.isSingleFile("installer.tar.gz"));
        assertFalse(Gzip.isSingleFile("plain.bin"));
        assertEquals("strange", Gzip.plainName("strange.gz"));
        assertEquals("installer.tar.gz", Gzip.plainName("installer.tar.gz"));
        assertEquals("plain.bin", Gzip.plainName("plain.bin"));
    }

    @Test
    void theDecompressedLimitFollowsTheCompressedSizeWithAFloor() {
        assertEquals(500, Gzip.limit(0, 500));
        assertEquals(500, Gzip.limit(-3, 500));
        assertEquals(Gzip.RATIO_FLOOR, Gzip.limit(10, Gzip.DEFAULT_MAX_BYTES), "a tiny file may still expand to the floor");
        assertEquals(5_000_000L * Gzip.MAX_RATIO, Gzip.limit(5_000_000, Gzip.DEFAULT_MAX_BYTES));
        assertEquals(2_000_000, Gzip.limit(5_000_000, 2_000_000), "the absolute cap wins");
    }

    // --- headers ---

    @Test
    void everyOptionalHeaderPartIsSkipped() throws Exception {
        final byte[] extra = {3, 0, 'a', 'b', 'c'};
        final byte[] name = "file.bin\0".getBytes(StandardCharsets.UTF_8);
        final byte[] comment = "a comment\0".getBytes(StandardCharsets.UTF_8);
        final byte[] crc16 = {1, 2};
        final ByteArrayOutputStream all = new ByteArrayOutputStream();
        all.writeBytes(extra);
        all.writeBytes(name);
        all.writeBytes(comment);
        all.writeBytes(crc16);
        assertArrayEquals(TEXT, gunzipped(gzip(8, 4 | 8 | 16 | 2, all.toByteArray(), TEXT, null)));
        assertArrayEquals(TEXT, gunzipped(gzip(8, 4, extra, TEXT, null)));
        assertArrayEquals(TEXT, gunzipped(gzip(8, 8, name, TEXT, null)));
        assertArrayEquals(TEXT, gunzipped(gzip(8, 16, comment, TEXT, null)));
        assertArrayEquals(TEXT, gunzipped(gzip(8, 2, crc16, TEXT, null)));
        assertArrayEquals(TEXT, gunzipped(plain(TEXT)));
    }

    static byte[] gunzipped(final byte[] gz) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final long n = Gzip.gunzip(new ByteArrayInputStream(gz), gz.length, Gzip.DEFAULT_MAX_BYTES, out);
        assertEquals(out.size(), n);
        return out.toByteArray();
    }

    @Test
    void aHeaderThatIsNotAGzipHeaderIsRefused() {
        assertTrue(refusal(new byte[5]).contains("not a gzip file"));
        assertTrue(refusal(new byte[0]).contains("not a gzip file"));
        final byte[] badMagic = plain(TEXT);
        badMagic[0] = 0;
        assertTrue(refusal(badMagic).contains("not a gzip file"));
        final byte[] badSecond = plain(TEXT);
        badSecond[1] = 0;
        assertTrue(refusal(badSecond).contains("not a gzip file"));
        assertTrue(refusal(gzip(7, 0, new byte[0], TEXT, null)).contains("unsupported compression method"));
        assertTrue(refusal(gzip(8, 0x20, new byte[0], TEXT, null)).contains("reserved flags"));
        assertTrue(refusal(gzip(8, 0x80, new byte[0], TEXT, null)).contains("reserved flags"));
    }

    @Test
    void aTruncatedOrOversizedHeaderPartIsRefused() {
        final byte[] fixed = Arrays.copyOf(plain(TEXT), 10);
        fixed[3] = 4;
        assertTrue(refusal(fixed).contains("gzip truncated"), "FEXTRA length missing");
        final byte[] shortExtra = Arrays.copyOf(fixed, 13);
        shortExtra[10] = 9;
        assertTrue(refusal(shortExtra).contains("gzip truncated"), "FEXTRA data shorter than announced");
        final byte[] noTerminator = Arrays.copyOf(plain(TEXT), 10);
        noTerminator[3] = 8;
        assertTrue(refusal(Arrays.copyOf(noTerminator, 14)).contains("gzip truncated"), "FNAME without its terminator");
        final byte[] longName = new byte[10 + 5000];
        System.arraycopy(noTerminator, 0, longName, 0, 10);
        Arrays.fill(longName, 10, longName.length, (byte) 'x');
        assertTrue(refusal(longName).contains("header too long"));
        final byte[] noCrc = Arrays.copyOf(plain(TEXT), 10);
        noCrc[3] = 2;
        assertTrue(refusal(Arrays.copyOf(noCrc, 11)).contains("gzip truncated"), "FHCRC cut short");
        final byte[] noComment = Arrays.copyOf(plain(TEXT), 10);
        noComment[3] = 16;
        assertTrue(refusal(Arrays.copyOf(noComment, 12)).contains("gzip truncated"), "FCOMMENT without its terminator");
    }

    // --- the data and the footer ---

    @Test
    void aStreamThatEndsBeforeTheDataDoes() {
        final byte[] gz = plain(TEXT);
        assertTrue(refusal(Arrays.copyOf(gz, 14)).contains("end of stream before the end of the data"));
        assertTrue(refusal(Arrays.copyOf(gz, 10)).contains("end of stream before the end of the data"));
    }

    @Test
    void damagedCompressedDataIsRefusedWithoutAnException() {
        final byte[] gz = plain(TEXT);
        gz[11] = (byte) 0xff;
        gz[12] = (byte) 0xff;
        assertTrue(refusal(gz).startsWith("gzip"), "refused as a gzip problem, whichever way the damage shows");
    }

    @Test
    void aMissingOrShortFooterIsRefused() {
        final byte[] gz = plain(TEXT);
        assertTrue(refusal(Arrays.copyOf(gz, gz.length - 8)).contains("footer missing"));
        assertTrue(refusal(Arrays.copyOf(gz, gz.length - 3)).contains("footer missing"));
    }

    @Test
    void bytesAfterTheFooterAreRefused() {
        final byte[] gz = plain(TEXT);
        final byte[] one = Arrays.copyOf(gz, gz.length + 1);
        assertTrue(refusal(one).contains("bytes after the footer"));
        final byte[] many = Arrays.copyOf(gz, gz.length + 40);
        assertTrue(refusal(many).contains("bytes after the footer"));
    }

    @Test
    void aWrongCrcOrSizeInTheFooterIsRefused() {
        final byte[] footer = footer(TEXT);
        final byte[] badCrc = footer.clone();
        badCrc[0] ^= 1;
        assertTrue(refusal(gzip(8, 0, new byte[0], TEXT, badCrc)).contains("CRC-32 differs"));
        final byte[] badSize = footer.clone();
        badSize[4] ^= 1;
        assertTrue(refusal(gzip(8, 0, new byte[0], TEXT, badSize)).contains("size differs"));
    }

    @Test
    void aBombIsRefusedByTheRatioOrByTheAbsoluteCap() throws IOException {
        final byte[] zeros = new byte[4 * 1024 * 1024];
        final byte[] gz = plain(zeros);
        assertTrue(gz.length < 10_000, "zeros compress very well: " + gz.length);
        final ReleaseManifest.Invalid ratio = assertThrows(ReleaseManifest.Invalid.class, () -> Gzip.gunzip(new ByteArrayInputStream(gz), gz.length, Gzip.DEFAULT_MAX_BYTES, new ByteArrayOutputStream()));
        assertTrue(ratio.getMessage().contains("decompression refused"), ratio.getMessage());
        final ReleaseManifest.Invalid cap = assertThrows(ReleaseManifest.Invalid.class, () -> Gzip.gunzip(new ByteArrayInputStream(plain(TEXT)), 0, 100, new ByteArrayOutputStream()));
        assertTrue(cap.getMessage().contains("more than 100 bytes"), cap.getMessage());
        assertEquals(zeros.length, Gzip.gunzip(new ByteArrayInputStream(gz), 0, Gzip.DEFAULT_MAX_BYTES, new ByteArrayOutputStream()), "without a compressed size, only the cap applies");
    }
}
