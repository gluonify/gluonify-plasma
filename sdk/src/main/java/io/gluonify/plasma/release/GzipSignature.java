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
 * Signature PAR FICHIER, dans l'en-tête gzip (RFC 1952, champ {@code FEXTRA}), à la manière d'un jeton signé : le fichier porte ses propres revendications (composant, architecture,
 * version, séquence, expiration, empreinte) et la signature Ed25519 de ces revendications. Un fichier {@code .gz} devient ainsi vérifiable seul, hors ligne, sans son manifeste.
 *
 * <p><b>Pas de circularité</b> : la signature est dans un sous-champ {@code GL} de {@code FEXTRA} ; l'empreinte signée est celle de la FORME NON SIGNÉE du fichier, c'est-à-dire le même
 * gzip dont le sous-champ {@code GL} est retiré (et {@code FEXTRA} retiré s'il ne reste rien). Elle couvre donc tout le reste : autres champs d'en-tête, nom, flux deflate, CRC-32 et
 * taille. Le manifeste liste ensuite le SHA-256 du fichier SIGNÉ tel que servi.
 *
 * <p><b>Sous-champ</b> : {@code 'G' 'L' LEN16} puis {@code 0x01 | u16 longueur des revendications | revendications (texte UTF-8) | signature Ed25519 de 64 octets}. Les revendications sont
 * signées telles quelles (octets exacts, rien n'est canonicalisé) et vérifiées AVANT d'être lues.
 *
 * <p><b>Ce que cela n'est pas</b> : un remplaçant du manifeste. La cohérence d'une version entière (tous les fichiers du même lot), le pointeur {@code latest}, les pierres tombales et la
 * rotation de clé restent portés par le manifeste et par le réservoir. Un fichier sans signature reste accepté tant que le manifeste est valide ; un fichier qui en porte une doit la
 * tenir (signature valide, non expirée, revendications égales à celles du manifeste).
 */
public final class GzipSignature {
    private GzipSignature() {}

    public static final int SI1 = 'G', SI2 = 'L', FORMAT = 1;
    static final String MAGIC = "gluonify-file 1";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    /** Revendications signées. {@code digest} : SHA-256 hexadécimal de la forme non signée. */
    public record Claims(String name, String arch, String version, long sequence, String expires, String digest) {
        public byte[] bytes() {
            return (MAGIC + "\nname=" + name + "\narch=" + arch + "\nversion=" + version + "\nsequence=" + sequence + "\nexpires=" + expires + "\ndigest=" + digest + "\n").getBytes(StandardCharsets.UTF_8);
        }

        static Claims parse(byte[] b) {
            String[] l = new String(b, StandardCharsets.UTF_8).split("\n", -1);
            if (l.length != 8 || !l[0].equals(MAGIC) || !l[7].isEmpty()) throw new ReleaseManifest.Invalid("signature de fichier : revendications illisibles");
            String name = value(l[1], "name"), arch = value(l[2], "arch"), version = value(l[3], "version"), seq = value(l[4], "sequence"), expires = value(l[5], "expires"), digest = value(l[6], "digest");
            if (!TOKEN.matcher(name).matches() || !ReleaseManifest.ARCHS.contains(arch) || !ReleaseManifest.VERSION.matcher(version).matches() || !digest.matches("[0-9a-f]{64}"))
                throw new ReleaseManifest.Invalid("signature de fichier : revendications invalides");
            try {
                Instant.parse(expires);
                return new Claims(name, arch, version, Long.parseLong(seq), expires, digest);
            } catch (RuntimeException e) {
                throw new ReleaseManifest.Invalid("signature de fichier : revendications invalides");
            }
        }

        private static String value(String line, String key) {
            if (!line.startsWith(key + "=")) throw new ReleaseManifest.Invalid("signature de fichier : « " + key + " » attendu");
            return line.substring(key.length() + 1);
        }
    }

    /** Ce que le vérificateur attend du fichier (null : non contrôlé). */
    public record Expected(String name, String arch, String version, Long sequence) {}

    /** Sous-champ lu : revendications (octets exacts) et signature. */
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

    /** En-tête lu : les 10 octets fixes, les sous-champs de FEXTRA, et la suite du flux (nom, deflate, pied de page). */
    private record Header(byte[] fixed, List<byte[]> subfields, byte[] gl) {}

    private static Header header(InputStream in) throws IOException {
        byte[] h = in.readNBytes(10);
        if (h.length < 10 || (h[0] & 0xff) != 0x1f || (h[1] & 0xff) != 0x8b) throw new ReleaseManifest.Invalid("ce n'est pas un fichier gzip (octets magiques absents)");
        int flags = h[3] & 0xff;
        if ((flags & 2) != 0) throw new ReleaseManifest.Invalid("gzip : FHCRC non pris en charge avec une signature de fichier");
        List<byte[]> subs = new ArrayList<>();
        byte[] gl = null;
        if ((flags & 4) != 0) {
            byte[] l = in.readNBytes(2);
            if (l.length < 2) throw new ReleaseManifest.Invalid("gzip tronqué");
            int n = (l[0] & 0xff) | ((l[1] & 0xff) << 8);
            byte[] extra = in.readNBytes(n);
            if (extra.length < n) throw new ReleaseManifest.Invalid("gzip tronqué");
            int p = 0;
            while (p < extra.length) {
                if (p + 4 > extra.length) throw new ReleaseManifest.Invalid("gzip : FEXTRA mal formé");
                int len = (extra[p + 2] & 0xff) | ((extra[p + 3] & 0xff) << 8);
                if (p + 4 + len > extra.length) throw new ReleaseManifest.Invalid("gzip : sous-champ FEXTRA hors limites");
                byte[] sf = java.util.Arrays.copyOfRange(extra, p, p + 4 + len);
                if ((sf[0] & 0xff) == SI1 && (sf[1] & 0xff) == SI2) {
                    if (gl != null) throw new ReleaseManifest.Invalid("gzip : plusieurs signatures de fichier");
                    gl = sf;
                } else {
                    subs.add(sf);
                }
                p += 4 + len;
            }
        }
        return new Header(h, subs, gl);
    }

    /** En-tête de la forme non signée (sous-champ GL retiré, FEXTRA retiré s'il est vide). */
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

    /** SHA-256 de la forme non signée du fichier (celle que la signature couvre). */
    public static String unsignedDigest(Path gz) throws IOException {
        return digest(gz);
    }

    /** La signature portée par le fichier, ou null s'il n'en a pas. Lève {@link ReleaseManifest.Invalid} si le gzip ou le sous-champ est mal formé. */
    public static Signed read(Path gz) throws IOException {
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(gz), 65536)) {
            Header h = header(in);
            if (h.gl() == null) return null;
            byte[] d = java.util.Arrays.copyOfRange(h.gl(), 4, h.gl().length);
            if (d.length < 1 + 2 + 64 || (d[0] & 0xff) != FORMAT) throw new ReleaseManifest.Invalid("signature de fichier : format inconnu");
            int cl = (d[1] & 0xff) | ((d[2] & 0xff) << 8);
            if (d.length != 3 + cl + 64) throw new ReleaseManifest.Invalid("signature de fichier : longueur incohérente");
            return new Signed(java.util.Arrays.copyOfRange(d, 3, 3 + cl), java.util.Arrays.copyOfRange(d, 3 + cl, d.length));
        }
    }

    /**
     * Signe un gzip NON signé : écrit dans {@code out} le même gzip avec le sous-champ GL. Refuse un fichier déjà signé. {@code name}, {@code arch}, {@code version}, {@code sequence} et
     * {@code expires} deviennent les revendications.
     */
    public static void sign(Path unsigned, Path out, String name, String arch, String version, long sequence, Instant expires, PrivateKey key) throws IOException {
        Header h;
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(unsigned), 65536)) {
            h = header(in);
        }
        if (h.gl() != null) throw new ReleaseManifest.Invalid("le fichier porte déjà une signature");
        Claims c = new Claims(name, arch, version, sequence, expires.toString(), digest(unsigned));
        byte[] cb = c.bytes();
        byte[] sig = ReleaseSigner.sign(cb, key);
        if (cb.length > 60000) throw new ReleaseManifest.Invalid("revendications trop longues");
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
        if (extraLen > 65535) throw new ReleaseManifest.Invalid("FEXTRA trop long");
        try (InputStream in = new java.io.BufferedInputStream(Files.newInputStream(unsigned), 65536); java.io.OutputStream o = new java.io.BufferedOutputStream(Files.newOutputStream(out), 65536)) {
            Header again = header(in); // positionne le flux après l'en-tête fixe et FEXTRA
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
     * Vérifie un fichier signé : signature valide pour l'une des clés, non expirée, empreinte égale à celle de la forme non signée recalculée, et revendications conformes à
     * {@code expected}. Retourne les revendications ; lève {@link ReleaseManifest.Invalid} sinon (y compris si le fichier n'est pas signé).
     */
    public static Claims verify(Path gz, Collection<PublicKey> keys, Instant now, Expected expected) throws IOException {
        Signed s = read(gz);
        if (s == null) throw new ReleaseManifest.Invalid("le fichier ne porte pas de signature");
        boolean valid = false;
        for (PublicKey k : keys) {
            try {
                Signature v = Signature.getInstance("Ed25519");
                v.initVerify(k);
                v.update(s.claimsBytes());
                if (v.verify(s.signature())) { valid = true; break; }
            } catch (GeneralSecurityException ignored) { }
        }
        if (!valid) throw new ReleaseManifest.Invalid("signature de fichier invalide (aucune clé de confiance ne la valide)");
        Claims c = s.claims();
        if (now.isAfter(Instant.parse(c.expires()))) throw new ReleaseManifest.Invalid("signature de fichier expirée (" + c.expires() + ")");
        if (!MessageDigest.isEqual(c.digest().getBytes(StandardCharsets.US_ASCII), digest(gz).getBytes(StandardCharsets.US_ASCII)))
            throw new ReleaseManifest.Invalid("signature de fichier : l'empreinte signée ne correspond pas au contenu (fichier modifié)");
        if (expected != null) {
            if (expected.name() != null && !expected.name().equals(c.name())) throw new ReleaseManifest.Invalid("signature de fichier : composant " + c.name() + " au lieu de " + expected.name());
            if (expected.arch() != null && !expected.arch().equals(c.arch())) throw new ReleaseManifest.Invalid("signature de fichier : architecture " + c.arch() + " au lieu de " + expected.arch());
            if (expected.version() != null && !expected.version().equals(c.version())) throw new ReleaseManifest.Invalid("signature de fichier : version " + c.version() + " au lieu de " + expected.version());
            if (expected.sequence() != null && expected.sequence() != c.sequence()) throw new ReleaseManifest.Invalid("signature de fichier : séquence " + c.sequence() + " au lieu de " + expected.sequence());
        }
        return c;
    }
}
