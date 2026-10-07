package io.gluonify.plasma.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Fichiers gzip des versions (voir gluonify-codex/docs/BOOTSTRAP.md, « Format des fichiers »).
 *
 * <p><b>Règle</b> : un fichier {@code .gz} qui n'est pas {@code .tar.gz} est un fichier UNIQUE compressé en gzip ; son nom décompressé est le nom sans {@code .gz}. Une archive
 * {@code .tar.gz} (l'image de l'installateur) est déjà compressée : elle n'est jamais recompressée ni décompressée ici.
 *
 * <p><b>Intégrité</b> : le gzip ne porte PAS de SHA-256. Son pied de page contient un CRC-32 et la taille modulo 2^32, qui ne détectent que la corruption accidentelle (un
 * attaquant les recalcule). La preuve d'intégrité et d'authenticité est l'empreinte SHA-256 du manifeste signé, vérifiée sur les octets TELS QUE TÉLÉCHARGÉS (le {@code .gz}),
 * AVANT toute décompression : {@link #gunzip} ne sert qu'ensuite.
 *
 * <p><b>Décompression bornée</b> : taille décompressée plafonnée ({@code maxBytes}) et rapport de décompression plafonné ({@link #MAX_RATIO}, contre les bombes) ; un seul membre,
 * aucun octet après le pied de page, CRC-32 et taille vérifiés ; tout écart lève {@link ReleaseManifest.Invalid}.
 */
public final class Gzip {
    private Gzip() {}

    /** Plafond de taille décompressée par défaut : 1 Gio. */
    public static final long DEFAULT_MAX_BYTES = 1024L * 1024 * 1024;
    /** Rapport décompressé / compressé au-delà duquel on refuse (un binaire natif compresse à 2-4 ; le gzip ne dépasse jamais ~1030). */
    public static final long MAX_RATIO = 100;
    /** Sous ce seuil de taille décompressée, le rapport n'est pas contrôlé (petits fichiers de test). */
    public static final long RATIO_FLOOR = 1024 * 1024;

    /** Fichier unique compressé : se termine par {@code .gz} sans être un {@code .tar.gz}. */
    public static boolean isSingleFile(String file) {
        return file != null && file.length() > 3 && file.endsWith(".gz") && !file.endsWith(".tar.gz");
    }

    /** Nom du fichier décompressé : sans {@code .gz} pour un fichier unique compressé, inchangé sinon. */
    public static String plainName(String file) {
        return isSingleFile(file) ? file.substring(0, file.length() - 3) : file;
    }

    /** Taille décompressée maximale admise pour un gzip de {@code compressedSize} octets. */
    public static long limit(long compressedSize, long maxBytes) {
        if (compressedSize <= 0) return maxBytes;
        return Math.min(maxBytes, Math.max(RATIO_FLOOR, compressedSize * MAX_RATIO));
    }

    /**
     * Décompresse en flux. Retourne le nombre d'octets écrits.
     *
     * @param compressedSize taille du gzip (pour le garde-fou de rapport) ; 0 ou moins : pas de contrôle de rapport
     * @param maxBytes       plafond absolu de la taille décompressée
     */
    public static long gunzip(InputStream in, long compressedSize, long maxBytes, OutputStream out) throws IOException {
        long limit = limit(compressedSize, maxBytes);
        byte[] h = in.readNBytes(10);
        if (h.length < 10 || (h[0] & 0xff) != 0x1f || (h[1] & 0xff) != 0x8b) throw new ReleaseManifest.Invalid("ce n'est pas un fichier gzip (octets magiques absents)");
        if (h[2] != 8) throw new ReleaseManifest.Invalid("gzip : méthode de compression non prise en charge");
        int flags = h[3] & 0xff;
        if ((flags & 0xe0) != 0) throw new ReleaseManifest.Invalid("gzip : drapeaux réservés");
        if ((flags & 4) != 0) { // FEXTRA
            byte[] l = in.readNBytes(2);
            if (l.length < 2) throw new ReleaseManifest.Invalid("gzip tronqué");
            int n = (l[0] & 0xff) | ((l[1] & 0xff) << 8);
            if (in.readNBytes(n).length < n) throw new ReleaseManifest.Invalid("gzip tronqué");
        }
        for (int bit : new int[] {8, 16}) { // FNAME, FCOMMENT : chaîne terminée par 0, bornée
            if ((flags & bit) == 0) continue;
            int c, n = 0;
            while ((c = in.read()) > 0) if (++n > 4096) throw new ReleaseManifest.Invalid("gzip : en-tête trop long");
            if (c < 0) throw new ReleaseManifest.Invalid("gzip tronqué");
        }
        if ((flags & 2) != 0 && in.readNBytes(2).length < 2) throw new ReleaseManifest.Invalid("gzip tronqué"); // FHCRC
        Inflater inf = new Inflater(true);
        CRC32 crc = new CRC32();
        byte[] ib = new byte[65536], ob = new byte[65536];
        long total = 0;
        int fed = 0;
        try {
            while (!inf.finished()) {
                if (inf.needsInput()) {
                    int n = in.read(ib);
                    if (n < 0) throw new ReleaseManifest.Invalid("gzip tronqué (fin du flux avant la fin des données)");
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
                    if (total > limit) throw new ReleaseManifest.Invalid("décompression refusée : plus de " + limit + " octets (plafond ou rapport de décompression dépassé)");
                    crc.update(ob, 0, w);
                    out.write(ob, 0, w);
                } else if (!inf.finished() && !inf.needsInput()) {
                    throw new ReleaseManifest.Invalid("gzip corrompu (dictionnaire ou flux invalide)");
                }
            }
            // pied de page : 8 octets, dont une partie peut déjà être dans le tampon ; rien ne doit suivre
            int rem = inf.getRemaining();
            byte[] t = new byte[8];
            if (rem > 8) throw new ReleaseManifest.Invalid("gzip : octets après le pied de page");
            if (rem > 0) System.arraycopy(ib, fed - rem, t, 0, rem);
            int got = rem;
            if (got < 8) {
                byte[] more = in.readNBytes(8 - got);
                System.arraycopy(more, 0, t, got, more.length);
                if (got + more.length < 8) throw new ReleaseManifest.Invalid("gzip tronqué (pied de page absent)");
            }
            if (in.read() >= 0) throw new ReleaseManifest.Invalid("gzip : octets après le pied de page");
            long wantCrc = (t[0] & 0xffL) | (t[1] & 0xffL) << 8 | (t[2] & 0xffL) << 16 | (t[3] & 0xffL) << 24;
            long wantLen = (t[4] & 0xffL) | (t[5] & 0xffL) << 8 | (t[6] & 0xffL) << 16 | (t[7] & 0xffL) << 24;
            if (wantCrc != crc.getValue()) throw new ReleaseManifest.Invalid("gzip corrompu : CRC-32 différent");
            if (wantLen != (total & 0xffffffffL)) throw new ReleaseManifest.Invalid("gzip corrompu : taille décompressée différente de celle du pied de page");
            return total;
        } finally {
            inf.end();
        }
    }
}
