package io.gluonify.plasma.release;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * PER-FILE signature, in the gzip header (RFC 1952, {@code FEXTRA} field), in the manner of a signed token: the file carries its own claims (component, architecture,
 * version, sequence, expiration, digest) and the Ed25519 signature of these claims. A {@code .gz} file thus becomes verifiable on its own, offline, without its manifest.
 *
 * <p><b>No circularity</b>: the signature is in a {@code GL} subfield of {@code FEXTRA}; the signed digest is that of the UNSIGNED FORM of the file, that is, the same
 * gzip with the {@code GL} subfield removed (and {@code FEXTRA} removed if nothing remains). It therefore covers everything else: other header fields, name, deflate stream, CRC-32 and
 * size. The manifest then lists the SHA-256 of the SIGNED file as served.
 *
 * <p><b>Subfield</b>: {@code 'G' 'L' LEN16} then {@code 0x01 | u16 length of the claims | claims (UTF-8 text) | 64-byte Ed25519 signature}. The claims are
 * signed as is (exact bytes, nothing is canonicalized) and verified BEFORE being read.
 *
 * <p><b>What this is not</b>: a replacement for the manifest. The consistency of an entire release (all the files of the same batch), the {@code latest} pointer, the tombstones and the
 * key rotation remain carried by the manifest and by the store. A file without a signature is still accepted as long as the manifest is valid; a file that carries one must
 * hold it (valid signature, not expired, claims equal to those of the manifest).
 */
public final class GzipSignature {
    private GzipSignature() {}

    public static final int SI1 = 'G', SI2 = 'L', FORMAT = 1;
    static final String MAGIC = "gluonify-file 1";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Signed claims. {@code digest}: hexadecimal SHA-256 of the unsigned form. */
    public record Claims(String name, String arch, String version, long sequence, String expires, String digest) {
        public byte[] bytes() {
            return (MAGIC + "\nname=" + name + "\narch=" + arch + "\nversion=" + version + "\nsequence=" + sequence + "\nexpires=" + expires + "\ndigest=" + digest + "\n").getBytes(StandardCharsets.UTF_8);
        }

        static Claims parse(byte[] b) {
            String[] l = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            if (l.length != 8 || !l[0].equals(MAGIC) || !l[7].isEmpty()) throw new ReleaseManifest.Invalid("file signature: unreadable claims");
            String name = value(l[1], "name"), arch = value(l[2], "arch"), version = value(l[3], "version"), seq = value(l[4], "sequence"), expires = value(l[5], "expires"), digest = value(l[6], "digest");
            if (!TOKEN.matcher(name).matches() || !ReleaseManifest.ARCHS.contains(arch) || !ReleaseManifest.VERSION.matcher(version).matches() || !digest.matches("[0-9a-f]{64}"))
                throw new ReleaseManifest.Invalid("file signature: invalid claims");
            try {
                Instant.parse(expires);
                return new Claims(name, arch, version, Long.parseLong(seq), expires, digest);
            } catch (RuntimeException e) {
                throw new ReleaseManifest.Invalid("file signature: invalid claims");
            }
        }

        private static String value(String line, String key) {
            if (!line.startsWith(key + "=")) throw new ReleaseManifest.Invalid("file signature: \"" + key + "\" expected");
            return line.substring(key.length() + 1);
        }
    }

    /** What the verifier expects of the file (null: not checked). */
    public record Expected(String name, String arch, String version, Long sequence) {}

    /** Subfield read: claims (exact bytes) and signature. */
    public record Signed(byte[] claimsBytes, byte[] signature) {
        @Override public boolean equals(Object o) {
            return o instanceof Signed s && java.util.Arrays.equals(claimsBytes, s.claimsBytes) && java.util.Arrays.equals(signature, s.signature);
        }

        @Override public int hashCode() {
            return 31 * java.util.Arrays.hashCode(claimsBytes) + java.util.Arrays.hashCode(signature);
        }

        @Override public String toString() {
            return "Signed[claimsBytes=" + claimsBytes.length + " bytes, signature=" + signature.length + " bytes]";
        }

        public Claims claims() {
            return Claims.parse(claimsBytes);
        }
    }

    /** Header read: the 10 fixed bytes, the FEXTRA subfields, and the rest of the stream (name, deflate, footer). */
    private record Header(byte[] fixed, List<byte[]> subfields, byte[] gl) {}

    private static Header header(InputStream in) throws IOException {
        byte[] h = in.readNBytes(10);
        if (h.length < 10 || (h[0] & 0xff) != 0x1f || (h[1] & 0xff) != 0x8b) throw new ReleaseManifest.Invalid("not a gzip file (magic bytes missing)");
        int flags = h[3] & 0xff;
        if ((flags & 2) != 0) throw new ReleaseManifest.Invalid("gzip: FHCRC not supported with a file signature");
        List<byte[]> subs = new ArrayList<>();
        byte[] gl = null;
        if ((flags & 4) != 0) {
            byte[] l = in.readNBytes(2);
            if (l.length < 2) throw new ReleaseManifest.Invalid("gzip truncated");
            int n = (l[0] & 0xff) | ((l[1] & 0xff) << 8);
            byte[] extra = in.readNBytes(n);
            if (extra.length < n) throw new ReleaseManifest.Invalid("gzip truncated");
            int p = 0;
            while (p < extra.length) {
                if (p + 4 > extra.length) throw new ReleaseManifest.Invalid("gzip: malformed FEXTRA");
                int len = (extra[p + 2] & 0xff) | ((extra[p + 3] & 0xff) << 8);
                if (p + 4 + len > extra.length) throw new ReleaseManifest.Invalid("gzip: FEXTRA subfield out of range");
                byte[] sf = java.util.Arrays.copyOfRange(extra, p, p + 4 + len);
                if ((sf[0] & 0xff) == SI1 && (sf[1] & 0xff) == SI2) {
                    if (gl != null) throw new ReleaseManifest.Invalid("gzip: several file signatures");
                    gl = sf;
                } else {
                    subs.add(sf);
                }
                p += 4 + len;
            }
        }
        return new Header(h, subs, gl);
    }

    /** Header of the unsigned form (GL subfield removed, FEXTRA removed if empty). */
    private static byte[] unsignedHeader(Header h) {
        byte[] f = h.fixed().clone();
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        int extraLen = h.subfields().stream().mapToInt(s -> s.length).sum();
        if (extraLen == 0) {
            f[3] = (byte) (f[3] & ~4);
            o.writeBytes(f);
        } else {
            f[3] = (byte) (f[3] | 4);
            o.writeBytes(f);
            o.write(extraLen & 0xff);
            o.write(extraLen >>> 8);
            for (byte[] s : h.subfields()) o.writeBytes(s);
        }
        return o.toByteArray();
    }

    private static String digest(Path gz) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(gz), 65536)) {
            Header h = header(in);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(unsignedHeader(h));
            byte[] buf = new byte[65536];
            for (int n; (n = in.read(buf)) > 0; ) md.update(buf, 0, n);
            return HexFormat.of().formatHex(md.digest());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** SHA-256 of the unsigned form of the file (the one the signature covers). */
    public static String unsignedDigest(Path gz) throws IOException {
        return digest(gz);
    }

    /** The signature carried by the file, or null if it has none. Raises {@link ReleaseManifest.Invalid} if the gzip or the subfield is malformed. */
    public static Signed read(Path gz) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(gz), 65536)) {
            Header h = header(in);
            if (h.gl() == null) return null;
            byte[] d = java.util.Arrays.copyOfRange(h.gl(), 4, h.gl().length);
            if (d.length < 1 + 2 + 64 || (d[0] & 0xff) != FORMAT) throw new ReleaseManifest.Invalid("file signature: unknown format");
            int cl = (d[1] & 0xff) | ((d[2] & 0xff) << 8);
            if (d.length != 3 + cl + 64) throw new ReleaseManifest.Invalid("file signature: inconsistent length");
            return new Signed(java.util.Arrays.copyOfRange(d, 3, 3 + cl), java.util.Arrays.copyOfRange(d, 3 + cl, d.length));
        }
    }

    /**
     * Signs an UNSIGNED gzip: writes to {@code out} the same gzip with the GL subfield. Refuses an already signed file. {@code name}, {@code arch}, {@code version}, {@code sequence} and
     * {@code expires} become the claims.
     */
    public static void sign(Path unsigned, Path out, String name, String arch, String version, long sequence, Instant expires, PrivateKey key) throws IOException {
        Header h;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(unsigned), 65536)) {
            h = header(in);
        }
        if (h.gl() != null) throw new ReleaseManifest.Invalid("the file already carries a signature");
        Claims c = new Claims(name, arch, version, sequence, expires.toString(), digest(unsigned));
        byte[] cb = c.bytes();
        byte[] sig = ReleaseSigner.sign(cb, key);
        if (cb.length > 60000) throw new ReleaseManifest.Invalid("claims too long");
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(FORMAT);
        data.write(cb.length & 0xff);
        data.write(cb.length >>> 8);
        data.writeBytes(cb);
        data.writeBytes(sig);
        byte[] gl = new byte[4 + data.size()];
        gl[0] = SI1;
        gl[1] = SI2;
        gl[2] = (byte) (data.size() & 0xff);
        gl[3] = (byte) (data.size() >>> 8);
        System.arraycopy(data.toByteArray(), 0, gl, 4, data.size());
        int extraLen = h.subfields().stream().mapToInt(s -> s.length).sum() + gl.length;
        if (extraLen > 65535) throw new ReleaseManifest.Invalid("FEXTRA too long");
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(unsigned), 65536); java.io.OutputStream o = new java.io.BufferedOutputStream(Files.newOutputStream(out), 65536)) {
            Header again = header(in); // positions the stream after the fixed header and FEXTRA
            byte[] f = again.fixed().clone();
            f[3] = (byte) (f[3] | 4);
            o.write(f);
            o.write(extraLen & 0xff);
            o.write(extraLen >>> 8);
            for (byte[] s : again.subfields()) o.write(s);
            o.write(gl);
            in.transferTo(o);
        }
    }

    /**
     * Verifies a signed file: signature valid for one of the keys, not expired, digest equal to that of the recomputed unsigned form, and claims matching
     * {@code expected}. Returns the claims; raises {@link ReleaseManifest.Invalid} otherwise (including if the file is not signed).
     */
    public static Claims verify(Path gz, Collection<PublicKey> keys, Instant now, Expected expected) throws IOException {
        Signed s = read(gz);
        if (s == null) throw new ReleaseManifest.Invalid("the file carries no signature");
        boolean valid = false;
        for (PublicKey k : keys) {
            try {
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(k);
                v.update(s.claimsBytes());
                if (v.verify(s.signature())) { valid = true; break; }
            } catch (GeneralSecurityException ignored) { }
        }
        if (!valid) throw new ReleaseManifest.Invalid("invalid file signature (no trusted key validates it)");
        Claims c = s.claims();
        if (now.isAfter(Instant.parse(c.expires()))) throw new ReleaseManifest.Invalid("file signature expired (" + c.expires() + ")");
        if (!MessageDigest.isEqual(c.digest().getBytes(StandardCharsets.US_ASCII), digest(gz).getBytes(StandardCharsets.US_ASCII)))
            throw new ReleaseManifest.Invalid("file signature: the signed digest does not match the content (file modified)");
        if (expected != null) {
            if (expected.name() != null && !expected.name().equals(c.name())) throw new ReleaseManifest.Invalid("file signature: component " + c.name() + " instead of " + expected.name());
            if (expected.arch() != null && !expected.arch().equals(c.arch())) throw new ReleaseManifest.Invalid("file signature: architecture " + c.arch() + " instead of " + expected.arch());
            if (expected.version() != null && !expected.version().equals(c.version())) throw new ReleaseManifest.Invalid("file signature: version " + c.version() + " instead of " + expected.version());
            if (expected.sequence() != null && expected.sequence() != c.sequence()) throw new ReleaseManifest.Invalid("file signature: sequence " + c.sequence() + " instead of " + expected.sequence());
        }
        return c;
    }
}
