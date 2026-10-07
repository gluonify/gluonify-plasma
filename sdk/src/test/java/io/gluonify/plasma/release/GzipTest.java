package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.time.Instant;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GzipTest {
    @TempDir Path dir;

    static byte[] gz(byte[] data) throws Exception {
        var b = new ByteArrayOutputStream();
        try (var g = new GZIPOutputStream(b)) { g.write(data); }
        return b.toByteArray();
    }

    static byte[] un(byte[] gz, long max) throws Exception {
        var o = new ByteArrayOutputStream();
        Gzip.gunzip(new ByteArrayInputStream(gz), gz.length, max, o);
        return o.toByteArray();
    }

    @Test void regleDesNoms() {
        assertTrue(Gzip.isSingleFile("gluonify-strange-linux-arm64.gz"));
        assertFalse(Gzip.isSingleFile("gluonify-node-image.tar.gz"));
        assertFalse(Gzip.isSingleFile("gluonify-strange-linux-arm64"));
        assertEquals("gluonify-strange-linux-arm64", Gzip.plainName("gluonify-strange-linux-arm64.gz"));
        assertEquals("gluonify-node-image.tar.gz", Gzip.plainName("gluonify-node-image.tar.gz"));
    }

    @Test void aller_retour() throws Exception {
        byte[] data = "contenu du binaire".repeat(1000).getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(data, un(gz(data), Gzip.DEFAULT_MAX_BYTES));
    }

    @Test void refus() throws Exception {
        byte[] data = new byte[200000];
        new java.util.Random(1).nextBytes(data);
        byte[] g = gz(data);
        assertThrows(ReleaseManifest.Invalid.class, () -> un(Arrays.copyOf(g, g.length - 20), Long.MAX_VALUE), "tronqué");
        assertThrows(ReleaseManifest.Invalid.class, () -> un(Arrays.copyOf(g, g.length - 3), Long.MAX_VALUE), "pied de page tronqué");
        byte[] crc = g.clone(); crc[crc.length - 8] ^= 1;
        assertThrows(ReleaseManifest.Invalid.class, () -> un(crc, Long.MAX_VALUE), "CRC");
        byte[] len = g.clone(); len[len.length - 1] ^= 1;
        assertThrows(ReleaseManifest.Invalid.class, () -> un(len, Long.MAX_VALUE), "taille du pied de page");
        byte[] body = g.clone(); body[body.length / 2] ^= 0x55;
        assertThrows(ReleaseManifest.Invalid.class, () -> un(body, Long.MAX_VALUE), "flux corrompu");
        assertThrows(ReleaseManifest.Invalid.class, () -> un("pas du gzip du tout".getBytes(), Long.MAX_VALUE), "octets magiques");
        byte[] trail = Arrays.copyOf(g, g.length + 1);
        assertThrows(ReleaseManifest.Invalid.class, () -> un(trail, Long.MAX_VALUE), "octet après le pied de page");
        assertThrows(ReleaseManifest.Invalid.class, () -> un(g, 1000), "plafond de taille");
    }

    @Test void bombeRefusee() throws Exception {
        byte[] g = gz(new byte[50 * 1024 * 1024]); // 50 Mio de zéros : quelques dizaines de Kio, rapport ~1000
        assertTrue(g.length < 100_000);
        assertThrows(ReleaseManifest.Invalid.class, () -> un(g, Gzip.DEFAULT_MAX_BYTES));
        // un petit fichier très compressible reste accepté (plancher)
        assertEquals(500_000, un(gz(new byte[500_000]), Gzip.DEFAULT_MAX_BYTES).length);
    }

    @Test void manifesteAvecGzEtPlain() throws Exception {
        Files.write(dir.resolve("gluonify-strange-linux-amd64.gz"), gz("a".getBytes()));
        Files.writeString(dir.resolve("gluonify-strange-linux-arm64"), "binaire brut");
        Files.writeString(dir.resolve("gluonify-node-image.tar.gz"), "image");
        ReleaseManifest m = ReleaseSigner.build(dir, "1.2.3", 7, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2027-01-01T00:00:00Z"));
        assertEquals(3, m.artifacts().size());
        var amd = m.find("gluonify-strange", "amd64").orElseThrow();
        assertTrue(amd.gzipped());
        assertEquals("gluonify-strange-linux-amd64", amd.plainFile());
        assertFalse(m.find("gluonify-strange", "arm64").orElseThrow().gzipped());
        assertFalse(m.find("gluonify-node-image", "arm64").orElseThrow().gzipped());
        KeyPair k = ReleaseTest.keys();
        byte[] bytes = m.toJson().getBytes(StandardCharsets.UTF_8);
        byte[] sig = ReleaseSigner.sign(bytes, k.getPrivate());
        assertEquals(m, ReleaseVerifier.verify(bytes, sig, k.getPublic(), ReleaseManifest::parse, 7, Instant.parse("2026-10-02T00:00:00Z")));
        // un nom avec séparateur ou « .. » reste refusé, .gz ou non
        var bad = new ReleaseManifest(1, "1.2.3", 7, m.createdAt(), m.expires(), java.util.List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "../x.gz", amd.sha256(), 5)));
        assertThrows(ReleaseManifest.Invalid.class, bad::validate);
    }
}
