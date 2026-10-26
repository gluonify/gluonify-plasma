package io.gluonify.plasma.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Gzip files of the releases (see gluonify-codex/docs/BOOTSTRAP.md, "File format: gzip").
 *
 * <p><b>Rule</b>: a {@code .gz} file that is not a {@code .tar.gz} is a SINGLE file compressed with gzip; its decompressed name is the name without {@code .gz}. A {@code .tar.gz}
 * archive (the installer image) is already compressed: it is never recompressed nor decompressed here.
 *
 * <p><b>Integrity</b>: the gzip carries NO SHA-256. Its footer contains a CRC-32 and the size modulo 2^32, which only detect accidental corruption (an
 * attacker recomputes them). The proof of integrity and authenticity is the SHA-256 digest in the signed manifest, verified on the bytes AS DOWNLOADED (the {@code .gz}),
 * BEFORE any decompression: {@link #gunzip} is only used afterwards.
 *
 * <p><b>Bounded decompression</b>: decompressed size capped ({@code maxBytes}) and decompression ratio capped ({@link #MAX_RATIO}, against bombs); a single member,
 * no byte after the footer, CRC-32 and size verified; any deviation raises {@link ReleaseManifest.Invalid}.
 */
public final class Gzip {
    private Gzip() {}

    /** Default decompressed size cap: 1 GiB. */
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024 * 1024;
    /** Decompressed / compressed ratio beyond which we refuse (a native binary compresses at 2-4; gzip never exceeds ~1030). */
    public static final long MAX_RATIO = 100;
    /** Below this decompressed size, the ratio is not checked (small test files). */
    public static final long RATIO_FLOOR = 1024L * 1024;

    /** Single compressed file: ends with {@code .gz} without being a {@code .tar.gz}. */
    public static boolean isSingleFile(final String file) {
        return file != null && file.length() > 3 && file.endsWith(".gz") && !file.endsWith(".tar.gz");
    }

    /** Name of the decompressed file: without {@code .gz} for a single compressed file, unchanged otherwise. */
    public static String plainName(final String file) {
        return isSingleFile(file) ? file.substring(0, file.length() - 3) : file;
    }

    /** Maximum decompressed size allowed for a gzip of {@code compressedSize} bytes. */
    public static long limit(final long compressedSize, final long maxBytes) {
        if (compressedSize <= 0) return maxBytes;
        return Math.min(maxBytes, Math.max(RATIO_FLOOR, compressedSize * MAX_RATIO));
    }

    /**
     * Decompresses as a stream. Returns the number of bytes written.
     *
     * @param compressedSize size of the gzip (for the ratio safeguard); 0 or less: no ratio check
     * @param maxBytes       absolute cap on the decompressed size
     */
    public static long gunzip(final InputStream in, final long compressedSize, final long maxBytes, final OutputStream out) throws IOException {
        long limit = limit(compressedSize, maxBytes);
        skipHeader(in);
        try (Ending ending = new Ending(new Inflater(true))) {
            Inflated r = inflateAll(in, ending.inflater, out, limit);
            verifyFooter(footer(in, ending.inflater, r), r);
            return r.total();
        }
    }

    /** Ends the inflater when closed (Inflater is not AutoCloseable before Java 25). */
    private static final class Ending implements java.io.Closeable {
        final Inflater inflater;

        Ending(final Inflater inflater) {
            this.inflater = inflater;
        }

        @Override
        public void close() {
            inflater.end();
        }
    }

    /** What inflating the data left: the bytes written, their CRC-32, the last input buffer and how much of it was filled. */
    private static final class Inflated {
        private final long total;
        private final long crc;
        private final byte[] input;
        private final int fed;

        Inflated(final long total, final long crc, final byte[] input, final int fed) {
            this.total = total;
            this.crc = crc;
            this.input = input;
            this.fed = fed;
        }

        long total() {
            return total;
        }

        long crc() {
            return crc;
        }

        byte[] input() {
            return input;
        }

        int fed() {
            return fed;
        }
    }

    /** Checks and skips the 10-byte header and its optional fields (FEXTRA, FNAME, FCOMMENT, FHCRC). */
    private static void skipHeader(final InputStream in) throws IOException {
        byte[] h = in.readNBytes(10);
        if (h.length < 10 || (h[0] & 0xff) != 0x1f || (h[1] & 0xff) != 0x8b) throw new ReleaseManifest.Invalid("not a gzip file (magic bytes missing)");
        if (h[2] != 8) throw new ReleaseManifest.Invalid("gzip: unsupported compression method");
        int flags = h[3] & 0xff;
        if ((flags & 0xe0) != 0) throw new ReleaseManifest.Invalid("gzip: reserved flags");
        if ((flags & 4) != 0) { // FEXTRA
            byte[] l = in.readNBytes(2);
            if (l.length < 2) throw new ReleaseManifest.Invalid("gzip truncated");
            int n = (l[0] & 0xff) | ((l[1] & 0xff) << 8);
            if (in.readNBytes(n).length < n) throw new ReleaseManifest.Invalid("gzip truncated");
        }
        for (int bit : new int[] {8, 16}) { // FNAME, FCOMMENT: 0-terminated string, bounded
            if ((flags & bit) != 0) skipString(in);
        }
        if ((flags & 2) != 0 && in.readNBytes(2).length < 2) throw new ReleaseManifest.Invalid("gzip truncated"); // FHCRC
    }

    private static void skipString(final InputStream in) throws IOException {
        int c;
        int n = 0;
        while ((c = in.read()) > 0) if (++n > 4096) throw new ReleaseManifest.Invalid("gzip: header too long");
        if (c < 0) throw new ReleaseManifest.Invalid("gzip truncated");
    }

    private static Inflated inflateAll(final InputStream in, final Inflater inf, final OutputStream out, final long limit) throws IOException {
        CRC32 crc = new CRC32();
        byte[] ib = new byte[65536];
        byte[] ob = new byte[65536];
        long total = 0;
        int fed = 0;
        while (!inf.finished()) {
            if (inf.needsInput()) {
                int n = in.read(ib);
                if (n < 0) throw new ReleaseManifest.Invalid("gzip truncated (end of stream before the end of the data)");
                inf.setInput(ib, 0, n);
                fed = n;
            }
            int w = inflate(inf, ob);
            if (w > 0) {
                total += w;
                if (total > limit) throw new ReleaseManifest.Invalid("decompression refused: more than " + limit + " bytes (cap or decompression ratio exceeded)");
                crc.update(ob, 0, w);
                out.write(ob, 0, w);
            } else if (!inf.finished() && !inf.needsInput()) {
                throw new ReleaseManifest.Invalid("gzip corrupted (invalid dictionary or stream)");
            }
        }
        return new Inflated(total, crc.getValue(), ib, fed);
    }

    private static int inflate(final Inflater inf, final byte[] ob) {
        try {
            return inf.inflate(ob);
        } catch (DataFormatException e) {
            throw new ReleaseManifest.Invalid("gzip corrompu : " + e.getMessage());
        }
    }

    /** The 8-byte footer, part of which may already be in the buffer; nothing must follow. */
    private static byte[] footer(final InputStream in, final Inflater inf, final Inflated r) throws IOException {
        int rem = inf.getRemaining();
        byte[] t = new byte[8];
        if (rem > 8) throw new ReleaseManifest.Invalid("gzip: bytes after the footer");
        if (rem > 0) System.arraycopy(r.input(), r.fed() - rem, t, 0, rem);
        if (rem < 8) {
            byte[] more = in.readNBytes(8 - rem);
            System.arraycopy(more, 0, t, rem, more.length);
            if (rem + more.length < 8) throw new ReleaseManifest.Invalid("gzip truncated (footer missing)");
        }
        if (in.read() >= 0) throw new ReleaseManifest.Invalid("gzip: bytes after the footer");
        return t;
    }

    private static void verifyFooter(final byte[] t, final Inflated r) {
        long wantCrc = (t[0] & 0xffL) | (t[1] & 0xffL) << 8 | (t[2] & 0xffL) << 16 | (t[3] & 0xffL) << 24;
        long wantLen = (t[4] & 0xffL) | (t[5] & 0xffL) << 8 | (t[6] & 0xffL) << 16 | (t[7] & 0xffL) << 24;
        if (wantCrc != r.crc()) throw new ReleaseManifest.Invalid("gzip corrupted: CRC-32 differs");
        if (wantLen != (r.total() & 0xffffffffL)) throw new ReleaseManifest.Invalid("gzip corrupted: decompressed size differs from the footer's");
    }
}
