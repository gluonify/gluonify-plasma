package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Publication key rotation: rules of {@link TrustedKeys}, format of {@link KeyRollover}, compatibility with a 0.1.0-style manifest. */
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

    /** Transition publication: manifest signed by the old key, announcement signed by the old key and countersigned by the new one. */
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

    @Test void fullRotationWithOverlapAndEnd() throws Exception {
        KeyPair o = gen(), n = gen();
        TrustedKeys tk = TrustedKeys.single(key(o));
        var a = apply(tk, transition(o, n, 5), 4, NOW);
        assertTrue(a.changed());
        assertEquals(key(n), a.keys().next());
        assertEquals(key(o), a.keys().current());
        assertEquals(RETIRE, a.keys().retireCurrentAfter());
        assertEquals(5, a.keys().sequence());
        // overlap: both keys accepted; after the switch: only the new one
        assertEquals(2, a.keys().accepted(NOW.plusSeconds(3600)).size());
        assertEquals(List.of(key(n)), a.keys().accepted(RETIRE.plusSeconds(1)));
        // a later manifest signed ONLY by the new key is accepted during the overlap, with or without the old one
        byte[] later = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 200)));
        var v = a.keys().verify(later, ReleaseSigner.sign(later, n.getPrivate()), null, ReleaseManifest::parse, 5, NOW.plusSeconds(86400));
        assertEquals(key(n), v.signer());
        // end of the rotation: the new key is alone; it requires a manifest signed by itself
        var fin = a.keys().finish(later, ReleaseSigner.sign(later, n.getPrivate()), null, 5, NOW.plusSeconds(86400));
        assertTrue(fin.changed());
        assertNull(fin.keys().next());
        assertEquals(key(n), fin.keys().current());
        assertEquals(List.of(key(n)), fin.keys().accepted(NOW));
        // the old key no longer signs anything accepted
        byte[] old = bytes(manifest("1.3.0", 9, NOW.plusSeconds(86400L * 200)));
        assertThrows(ReleaseManifest.Invalid.class, () -> fin.keys().verify(old, ReleaseSigner.sign(old, o.getPrivate()), null, ReleaseManifest::parse, 5, NOW));
        // finishing twice: nothing to do
        assertFalse(fin.keys().finish(later, ReleaseSigner.sign(later, n.getPrivate()), null, 5, NOW).changed());
    }

    @Test void finishRefusesAManifestSignedByTheOldKey() throws Exception {
        KeyPair o = gen(), n = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        byte[] m = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 200)));
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().finish(m, ReleaseSigner.sign(m, o.getPrivate()), null, 0, NOW));
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().finish(m, ReleaseSigner.sign(m, gen().getPrivate()), null, 0, NOW));
    }

    @Test void downgradeAfterTheSwitchRefused() throws Exception {
        KeyPair o = gen(), n = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        byte[] m = bytes(manifest("1.2.0", 6, NOW.plusSeconds(86400L * 300)));
        byte[] sigOld = ReleaseSigner.sign(m, o.getPrivate());
        // before the switch: accepted; after: refused with an explicit message
        assertNotNull(a.keys().verify(m, sigOld, null, ReleaseManifest::parse, 0, RETIRE.minusSeconds(10)));
        var e = assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().verify(m, sigOld, null, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
        assertTrue(e.getMessage().contains("retired"), e.getMessage());
        // even the transition version, replayed after the switch with only its old signature
        Transition t = transition(o, n, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> a.keys().verify(t.manifest, t.sig, null, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
        // ... but accepted through its countersignature by the new key
        assertNotNull(a.keys().verify(t.manifest, t.sig, t.sig2, ReleaseManifest::parse, 0, RETIRE.plusSeconds(10)));
    }

    @Test void attacksAgainstTheAnnouncement() throws Exception {
        KeyPair o = gen(), n = gen(), x = gen();
        TrustedKeys tk = TrustedKeys.single(key(o));
        Transition t = transition(o, n, 5);
        // announcement signed by ANOTHER key than the trusted one (or manifest signed by an unknown one)
        Transition badManifest = new Transition(t.manifest, ReleaseSigner.sign(t.manifest, x.getPrivate()), t.sig2, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badManifest, 0, NOW));
        Transition badRollSig = new Transition(t.manifest, t.sig, t.sig2, t.rollover, ReleaseSigner.sign(t.rollover, x.getPrivate()), t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badRollSig, 0, NOW));
        // no countersignature by the new key / countersignature by another key (typo: lock-out)
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, t.sig2, t.rollover, t.rsig, null, 0, NOW));
        Transition badCross = new Transition(t.manifest, t.sig, t.sig2, t.rollover, t.rsig, ReleaseSigner.sign(t.rollover, x.getPrivate()));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badCross, 0, NOW));
        // countersignature of the manifest by another key
        Transition badSig2 = new Transition(t.manifest, t.sig, ReleaseSigner.sign(t.manifest, x.getPrivate()), t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, badSig2, 0, NOW));
        // announcement of ANOTHER manifest (replay of an old announcement on a new manifest)
        Transition other = transition(o, n, 6);
        Transition mixed = new Transition(other.manifest, other.sig, other.sig2, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, mixed, 0, NOW));
        // tampered announcement (key replaced): the signature no longer holds
        byte[] swapped = new String(t.rollover, StandardCharsets.UTF_8).replace(key(n).der(), key(x).der()).getBytes(StandardCharsets.UTF_8);
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, t.sig2, swapped, t.rsig, t.rsig2, 0, NOW));
        // expired manifest, sequence behind, sequence already used by a key change
        Transition expired = transition(o, n, 5, NOW, RETIRE, NOW.plusSeconds(10));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, expired, 0, NOW.plusSeconds(60)));
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, t, 9, NOW)); // sequence 5 < 9: replay of an old manifest
        TrustedKeys had = new TrustedKeys(key(o), null, null, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(had, t, 0, NOW));
        // switch date already past
        Transition late = transition(o, n, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, late, 0, RETIRE.plusSeconds(1)));
        // announcement without countersignature or signature
        assertThrows(ReleaseManifest.Invalid.class, () -> tk.applyRollover(t.manifest, t.sig, null, t.rollover, null, null, 0, NOW));
        // announcement of the key that already signs
        Transition self = transition(o, o, 5);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, self, 0, NOW));
        // the countersignature alone is not enough to carry the announcement (the manifest must be signed by the trusted key)
        Transition onlyNew = new Transition(t.manifest, ReleaseSigner.sign(t.manifest, n.getPrivate()), null, t.rollover, t.rsig, t.rsig2);
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(tk, onlyNew, 0, NOW));
    }

    @Test void idempotenceAndConcurrentRotation() throws Exception {
        KeyPair o = gen(), n = gen(), m = gen();
        var a = apply(TrustedKeys.single(key(o)), transition(o, n, 5), 0, NOW);
        var again = apply(a.keys(), transition(o, n, 5), 5, NOW);
        assertFalse(again.changed());
        assertEquals(a.keys(), again.keys());
        // another announcement during an ongoing rotation is refused
        assertThrows(ReleaseManifest.Invalid.class, () -> apply(a.keys(), transition(o, m, 6), 5, NOW));
    }

    @Test void keyFileRoundTripAndStrictReading() throws Exception {
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

    @Test void announcementFormatStrict() throws Exception {
        KeyPair o = gen(), n = gen();
        Transition t = transition(o, n, 5);
        KeyRollover r = KeyRollover.parse(t.rollover);
        assertEquals(key(n).sha256(), r.nextKeySha256());
        String good = new String(t.rollover, StandardCharsets.UTF_8);
        for (String bad : new String[]{good.replace("\"schema\":1", "\"schema\":2"), good + " ", good.replace("\n\"nextKey\"", ",\n\"nextKey\""), good.replace(key(n).sha256(), "f".repeat(64)),
                good.replace("\"retireOldAfter\":\"" + RETIRE, "\"retireOldAfter\":\"" + NOW.minusSeconds(5)), good.replace("\"nextKey\":\"", "\"nextKey\":\"AAAA"), good.replace("\"version\":\"1.1.0\"", "\"version\":\"../x\"")}) {
            assertThrows(ReleaseManifest.Invalid.class, () -> KeyRollover.parse(bad.getBytes(StandardCharsets.UTF_8)), bad);
        }
        // excessive overlap (stolen key announcing a 10-year overlap)
        assertThrows(ReleaseManifest.Invalid.class, () -> transition(o, n, 5, NOW, NOW.plusSeconds(86400L * 3650), NOW.plusSeconds(86400L * 90)));
    }

    /** A manifest signed "the 0.1.0 way" (unchanged format) is still verified by the old path (a single key, ReleaseVerifier.verify); the rotation files are ignored. */
    @Test void manifestOfStyle010StillVerifiedByAnOldVerifier() throws Exception {
        KeyPair o = gen(), n = gen();
        Transition t = transition(o, n, 5);
        // 0.1.0 path: single key, strict parse, no reading of the rollover.json or .sig2 files
        ReleaseManifest m = ReleaseVerifier.verify(t.manifest, Base64.getEncoder().encode(t.sig), o.getPublic(), ReleaseVerifier.Policy.of(ReleaseManifest::parse, 0, NOW));
        assertEquals("1.1.0", m.version());
        // the transition manifest is byte for byte what 0.1.0 produced (no field added)
        assertEquals(m.toJson(), new String(t.manifest, StandardCharsets.UTF_8));
        // and a manifest carrying a keys field would be refused by that same old verifier: that is WHY the announcement is in a separate file
        String withKeys = new String(t.manifest, StandardCharsets.UTF_8).replace("\"artifacts\":[", "\"nextKey\":\"x\",\n\"artifacts\":[");
        byte[] wk = withKeys.getBytes(StandardCharsets.UTF_8);
        final byte[] wkSig = ReleaseSigner.sign(wk, o.getPrivate());
        final PublicKey original = o.getPublic();
        final ReleaseVerifier.Policy any = ReleaseVerifier.Policy.of(ReleaseManifest::parse, 0, NOW);
        assertThrows(ReleaseManifest.Invalid.class, () -> ReleaseVerifier.verify(wk, wkSig, original, any));
        // a 0.1.0 node has only one key: the same verification through the new set of original keys gives the same result
        assertEquals(m, TrustedKeys.single(key(o)).verify(t.manifest, t.sig, null, ReleaseManifest::parse, 0, NOW).manifest());
        // and the new key is worth nothing to it
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
