package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rotation de la clé de publication : règles de {@link TrustedKeys}, format de {@link KeyRollover}, compatibilité avec un manifeste de style 0.1.0. */
class KeyRotationTest {
    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final Instant RETIRE = NOW.plusSeconds(86400L * 90);

    static KeyPair gen() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static TrustedKeys.Key key(KeyPair k) {
        return TrustedKeys.Key.ofDer(Base64.getEncoder().encodeToString(k.getPublic().getEncoded()));
    }

    static ReleaseManifest manifest(String version, long seq, Instant expires) {
        return new ReleaseManifest(1, version, seq, NOW.toString(), expires.toString(),
                List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "gluonify-strange-linux-amd64", "ab".repeat(32), 100)));
    }

    static byte[] bytes(ReleaseManifest m) {
        return m.toJson().getBytes(StandardCharsets.UTF_8);
    }

    /** Publication de transition : manifeste signé par l'ancienne clé, annonce signée par l'ancienne et contresignée par la nouvelle. */
    record Transition(byte[] manifest, byte[] sig, byte[] sig2, byte[] rollover, byte[] rsig, byte[] rsig2) {}

    static Transition transition(KeyPair oldK, KeyPair newK, long seq, Instant created, Instant retire, Instant expires) {
        ReleaseManifest m = manifest("1.1.0", seq, expires);
        byte[] mb = bytes(m);
        byte[] rb = ReleaseSigner.rollover(mb, m, newK.getPublic(), created, retire).toJson().getBytes(StandardCharsets.UTF_8);
        return new Transition(mb, ReleaseSigner.sign(mb, oldK.getPrivate()), ReleaseSigner.sign(mb, newK.getPrivate()), rb, ReleaseSigner.sign(rb, oldK.getPrivate()), ReleaseSigner.sign(rb, newK.getPrivate()));
    }

    static Transition transition(KeyPair oldK, KeyPair newK, long seq) {
        return transition(oldK, newK, seq, NOW, RETIRE, NOW.plusSeconds(86400L * 200));
    }

    TrustedKeys.Applied apply(TrustedKeys tk, Transition t, long seen, Instant now) {
        return tk.applyRollover(t.manifest, t.sig, t.sig2, t.rollover, t.rsig, t.rsig2, seen, now);
    }

    @Test void rotationCompleteAvecRecouvrementEtFin() throws Exception {
        KeyPair o = gen(), n = gen();
        TrustedKeys tk = TrustedKeys.single(key(o));
        var a = apply(tk, transition(o, n, 5), 4, NOW);
        assertTrue(a.changed());
        assertEquals(key(n), a.keys().next());
        assertEquals(key(o), a.keys().current());
        assertEquals(RETIRE, a.keys().retireCurrentAfter());
        assertEquals(5, a.keys().sequence());
        // recouvrement : les deux clés acceptées ; après la bascule : seulement la nouvelle
        assertEquals(2, a.keys().accepted(NOW.plusSeconds(3600)).size());
        assertEquals(List.of(key(n)), a.keys().accepted(RETIRE.plusSeconds(1)));
        // un manifeste ultérieur signé SEULEMENT par la nouvelle clé est accepté pendant le recouvrement, avec ou sans l'ancienne
        byte[] later = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 200)));
        var v = a.keys().verify(later, ReleaseSigner.sign(later, n.getPrivate()), null, ReleaseManifest::parse, 5, NOW.plusSeconds(86400));
        assertEquals(key(n), v.signer());
        // fin de la rotation : la nouvelle clé est seule ; elle exige un manifeste signé par elle
        var fin = a.keys().finish(later, ReleaseSigner.sign(later, n.getPrivate()), null, 5, NOW.plusSeconds(86400));
        assertTrue(fin.changed());
        assertNull(fin.keys().next());
        assertEquals(key(n), fin.keys().current());
        assertEquals(List.of(key(n)), fin.keys().accepted(NOW));
        // l'ancienne clé ne signe plus rien d'accepté
        byte[] old = bytes(manifest("1.3.0", 9, NOW.plusSeconds(86400L * 200)));
        assertThrows(ReleaseManifest.Invalid.class, () -> fin.keys().verify(old, ReleaseSigner.sign(old, o.getPrivate()), null, ReleaseManifest::parse, 5, NOW));
        // terminer deux fois : rien à faire
        assertFalse(fin.keys().finish(later, ReleaseSigner.sign(later, n.getPrivate()), null, 5, NOW).changed());
    }

    @Test void finishRefuseUnManifesteSigneParLAncienneCle() throws Exception {
        KeyPair o = gen(), n = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        byte[] m = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 200)));
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().finish(m, ReleaseSigner.sign(m, o.getPrivate()), null, 0, NOW));
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().finish(m, ReleaseSigner.sign(m, gen().getPrivate()), null, 0, NOW));
    }

    @Test void downgradeApresLaBasculeRefuse() throws Exception {
        KeyPair o = gen(), n = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        byte[] m = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 300)));
        byte[] sigOld = ReleaseSigner.sign(m, o.getPrivate());
        // avant la bascule : accepté ; après : refusé avec un message explicite
        assertNotNull(a.keys().verify(m, sigOld, null, ReleaseManifest::parse, 0, RETIRE.minusSeconds(10)));
        var e = assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().verify(m, sigOld, null, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
        assertTrue(e.getMessage().contains("retirée"), e.getMessage());
        // même la version de transition, rejouée après la bascule avec sa seule signature ancienne
        Transition t = transition(o, n, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().verify(t.manifest, t.sig, null, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
        // … mais acceptée par sa contresignature de la nouvelle clé
        assertNotNull(a.keys().verify(t.manifest, t.sig, t.sig2, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
    }

    @Test void attaquesContreLAnnonce() throws Exception {
        KeyPair o = gen(), n = gen(), x = gen();
        TrustedKeys tk = TrustedKeys.single(key(o));
        Transition t = transition(o, n, 5);
        // annonce signée par une AUTRE clé que celle de confiance (ou manifeste signé par une inconnue)
        Transition badManifest = new Transition(t.manifest, ReleaseSigner.sign(t.manifest, x.getPrivate()), t.sig2, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badManifest, 0, NOW));
        Transition badRollSig = new Transition(t.manifest, t.sig, t.sig2, t.rollover, ReleaseSigner.sign(t.rollover, x.getPrivate()), t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badRollSig, 0, NOW));
        // pas de contresignature de la nouvelle clé / contresignature d'une autre clé (faute de frappe : verrouillage)
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, t.sig2, t.rollover, t.rsig, null, 0, NOW));
        Transition badCross = new Transition(t.manifest, t.sig, t.sig2, t.rollover, t.rsig, ReleaseSigner.sign(t.rollover, x.getPrivate()));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badCross, 0, NOW));
        // contresignature du manifeste par une autre clé
        Transition badSig2 = new Transition(t.manifest, t.sig, ReleaseSigner.sign(t.manifest, x.getPrivate()), t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badSig2, 0, NOW));
        // annonce d'un AUTRE manifeste (rejeu d'une annonce ancienne sur un nouveau manifeste)
        Transition other = transition(o, n, 6);
        Transition mixed = new Transition(other.manifest, other.sig, other.sig2, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, mixed, 0, NOW));
        // annonce altérée (clé remplacée) : la signature ne tient plus
        byte[] swapped = new String(t.rollover, StandardCharsets.UTF_8).replace(key(n).der(), key(x).der()).getBytes(StandardCharsets.UTF_8);
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, t.sig2, swapped, t.rsig, t.rsig2, 0, NOW));
        // manifeste expiré, séquence en retard, séquence déjà utilisée par un changement de clé
        Transition expired = transition(o, n, 5, NOW, RETIRE, NOW.plusSeconds(10));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, expired, 0, NOW.plusSeconds(60)));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, t, 9, NOW)); // séquence 5 < 9 : rejeu d'un vieux manifeste
        TrustedKeys had = new TrustedKeys(key(o), null, null, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(had, t, 0, NOW));
        // date de bascule déjà passée
        Transition late = transition(o, n, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, late, 0, RETIRE.plusSeconds(1)));
        // annonce sans contresignature ni signature
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, null, t.rollover, null, null, 0, NOW));
        // annonce de la clé qui signe déjà
        Transition self = transition(o, o, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, self, 0, NOW));
        // la contresignature seule ne suffit pas à porter l'annonce (le manifeste doit être signé par la clé de confiance)
        Transition onlyNew = new Transition(t.manifest, ReleaseSigner.sign(t.manifest, n.getPrivate()), null, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, onlyNew, 0, NOW));
    }

    @Test void idempotenceEtRotationConcurrente() throws Exception {
        KeyPair o = gen(), n = gen(), m = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        var again = apply(a.keys(), transition(o, n, 5), 5, NOW);
        assertFalse(again.changed());
        assertEquals(a.keys(), again.keys());
        // une autre annonce pendant une rotation en cours est refusée
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(a.keys(), transition(o, m, 6), 5, NOW));
    }

    @Test void fichierDeClesAllerRetourEtLectureStricte() throws Exception {
        KeyPair o = gen(), n = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        assertEquals(a.keys(), TrustedKeys.parse(a.keys().toText()));
        assertEquals(TrustedKeys.single(key(o)), TrustedKeys.parse(TrustedKeys.single(key(o)).toText()));
        String ok = a.keys().toText();
        for (String bad : new String[]{"", "x\n", ok.replace("current ", "curent "), ok + "extra\n", ok.replace("sequence 5", "sequence -5"), ok.replace(key(n).sha256(), "0".repeat(64)),
                ok.substring(0, ok.length() - 1), ok.replace("gluonify-release-keys 1", "gluonify-release-keys 2")}) {
            assertThrows(ReleaseManifest.Invalid.class, () -> TrustedKeys.parse(bad), bad);
        }
        assertTrue(key(o).pem().startsWith("-----BEGIN PUBLIC KEY-----\n"));
        assertEquals(key(o), TrustedKeys.Key.ofPem(key(o).pem()));
    }

    @Test void formatDeLAnnonceStrict() throws Exception {
        KeyPair o = gen(), n = gen();
        Transition t = transition(o, n, 5);
        KeyRollover r = KeyRollover.parse(t.rollover);
        assertEquals(key(n).sha256(), r.nextKeySha256());
        String good = new String(t.rollover, StandardCharsets.UTF_8);
        for (String bad : new String[]{good.replace("\"schema\":1", "\"schema\":2"), good + " ", good.replace("\n\"nextKey\"", ",\n\"nextKey\""), good.replace(key(n).sha256(), "f".repeat(64)),
                good.replace("\"retireOldAfter\":\"" + RETIRE, "\"retireOldAfter\":\"" + NOW.minusSeconds(5)), good.replace("\"nextKey\":\"", "\"nextKey\":\"AAAA"), good.replace("\"version\":\"1.1.0\"", "\"version\":\"../x\"")}) {
            assertThrows(ReleaseManifest.Invalid.class, () -> KeyRollover.parse(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        // recouvrement démesuré (clé volée qui annoncerait un recouvrement de 10 ans)
        assertThrows(ReleaseManifest.Invalid.class, () -> transition(o, n, 5, NOW, NOW.plusSeconds(86400L * 3650), NOW.plusSeconds(86400L * 90)));
    }

    /** Un manifeste signé « à la 0.1.0 » (format inchangé) reste vérifié par l'ancien chemin (une seule clé, ReleaseVerifier.verify) ; les fichiers de rotation sont ignorés. */
    @Test void manifesteDeStyle010ToujoursVerifieParUnVerificateurAncien() throws Exception {
        KeyPair o = gen(), n = gen();
        Transition t = transition(o, n, 5);
        // chemin de la 0.1.0 : clé unique, parse strict, aucune lecture des fichiers rollover.json ni .sig2
        ReleaseManifest m = ReleaseVerifier.verify(t.manifest, Base64.getEncoder().encode(t.sig), o.getPublic(), ReleaseManifest::parse, 0, NOW);
        assertEquals("1.1.0", m.version());
        // le manifeste de transition est octet pour octet celui que produisait la 0.1.0 (aucun champ ajouté)
        assertEquals(m.toJson(), new String(t.manifest, StandardCharsets.UTF_8));
        // et un manifeste qui porterait un champ de clés serait refusé par ce même vérificateur ancien : c'est POURQUOI l'annonce est dans un fichier séparé
        String withKeys = new String(t.manifest, StandardCharsets.UTF_8).replace("\"artifacts\":[", "\"nextKey\":\"x\",\n\"artifacts\":[");
        byte[] wk = withKeys.getBytes(StandardCharsets.UTF_8);
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(wk, ReleaseSigner.sign(wk, o.getPrivate()), o.getPublic(), ReleaseManifest::parse, 0, NOW));
        // un nœud 0.1.0 n'a qu'une clé : la même vérification par le nouvel ensemble de clés d'origine donne le même résultat
        assertEquals(m, TrustedKeys.single(key(o)).verify(t.manifest, t.sig, null, ReleaseManifest::parse, 0, NOW).manifest());
        // et la nouvelle clé ne vaut rien pour lui
        assertThrows(ReleaseManifest.Invalid.class, () -> TrustedKeys.single(key(o)).verify(t.manifest, ReleaseSigner.sign(t.manifest, n.getPrivate()), null, ReleaseManifest::parse, 0, NOW));
    }

    @Test void catalogueRotateReleaseKey() {
        assertEquals(Map.of("version", "1.1.0"), TaskCatalogue.validate("rotate-release-key", Map.of("version", "1.1.0"), TaskCatalogue.AGENT));
        assertEquals(Map.of("version", "1.1.0", "finalize", "true"), TaskCatalogue.validate("rotate-release-key", Map.of("version", "1.1.0", "finalize", "true"), TaskCatalogue.BOOTSTRAP));
        for (Map<String, String> bad : List.of(Map.<String, String>of(), Map.of("version", "../../etc"), Map.of("version", "1.1.0", "finalize", "yes"), Map.of("version", "1.1.0", "key", "MCowBQYDK2VwAyEA"),
                Map.of("version", "1.1.0", "url", "https://evil.example"), Map.of("version", "1.1.0", "path", "/etc/gluonify/release.pub"), Map.of("version", "1.1.0; id"))) {
            assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("rotate-release-key", bad, TaskCatalogue.AGENT), bad.toString());
        }
    }
}
