package io.gluonify.plasma.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Gzip files of the releases (see gluonify-codex/docs/BOOTSTRAP.md, "Format des fichiers").
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
    public static boolean isSingleFile(String file) {
        return file != null && file.length() > 3 && file.endsWith(".gz") && !file.endsWith(".tar.gz");
    }

    /** Name of the decompressed file: without {@code .gz} for a single compressed file, unchanged otherwise. */
    public static String plainName(String file) {
        return isSingleFile(file) ? file.substring(0, file.length() - 3) : file;
    }

    /** Maximum decompressed size allowed for a gzip of {@code compressedSize} bytes. */
    public static long limit(long compressedSize, long maxBytes) {
        if (compressedSize <= 0) return maxBytes;
        return Math.min(maxBytes, Math.max(RATIO_FLOOR, compressedSize * MAX_RATIO));
    }

    /**
     * Decompresses as a stream. Returns the number of bytes written.
     *
     * @param compressedSize size of the gzip (for the ratio safeguard); 0 or less: no ratio check
     * @param maxBytes       absolute cap on the decompressed size
     */
    public static long gunzip(InputStream in, long compressedSize, long maxBytes, OutputStream out) throws IOException {
        long limit = limit(compressedSize, maxBytes);
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
            if ((flags & bit) == 0) continue;
            int c, n = 0;
            while ((c = in.read()) > 0) if (++n > 4096) throw new ReleaseManifest.Invalid("gzip: header too long");
            if (c < 0) throw new ReleaseManifest.Invalid("gzip truncated");
        }
        if ((flags & 2) != 0 && in.readNBytes(2).length < 2) throw new ReleaseManifest.Invalid("gzip truncated"); // FHCRC
        Inflater inf = new Inflater(true);
        CRC32 crc = new CRC32();
        byte[] ib = new byte[65536], ob = new byte[65536];
        long total = 0;
        int fed = 0;
        try {
            while (!inf.finished()) {
                if (inf.needsInput()) {
                    int n = in.read(ib);
                    if (n < 0) throw new ReleaseManifest.Invalid("gzip truncated (end of stream before the end of the data)");
                    inf.setInput(ib, 0, n);
                    fed = n;
                }
                int w;
                try {
                    w = inf.inflate(ob);
                } catch (DataFormatException e) {
                    throw new ReleaseManifest.Invalid("gzip corrompu : " + e.getMessage());
                }
                if (w > 0) {
                    total += w;
                    if (total > limit) throw new ReleaseManifest.Invalid("decompression refused: more than " + limit + " bytes (cap or decompression ratio exceeded)");
                    crc.update(ob, 0, w);
                    out.write(ob, 0, w);
                } else if (!inf.finished() && !inf.needsInput()) {
                    throw new ReleaseManifest.Invalid("gzip corrupted (invalid dictionary or stream)");
                }
            }
            // footer: 8 bytes, part of which may already be in the buffer; nothing must follow
            int rem = inf.getRemaining();
            byte[] t = new byte[8];
            if (rem > 8) throw new ReleaseManifest.Invalid("gzip: bytes after the footer");
            if (rem > 0) System.arraycopy(ib, fed - rem, t, 0, rem);
            int got = rem;
            if (got < 8) {
                byte[] more = in.readNBytes(8 - got);
                System.arraycopy(more, 0, t, got, more.length);
                if (got + more.length < 8) throw new ReleaseManifest.Invalid("gzip truncated (footer missing)");
            }
            if (in.read() >= 0) throw new ReleaseManifest.Invalid("gzip: bytes after the footer");
            long wantCrc = (t[0] & 0xffL) | (t[1] & 0xffL) << 8 | (t[2] & 0xffL) << 16 | (t[3] & 0xffL) << 24;
            long wantLen = (t[4] & 0xffL) | (t[5] & 0xffL) << 8 | (t[6] & 0xffL) << 16 | (t[7] & 0xffL) << 24;
            if (wantCrc != crc.getValue()) throw new ReleaseManifest.Invalid("gzip corrupted: CRC-32 differs");
            if (wantLen != (total & 0xffffffffL)) throw new ReleaseManifest.Invalid("gzip corrupted: decompressed size differs from the footer's");
            return total;
        } finally {
            inf.end();
        }
    }
}
