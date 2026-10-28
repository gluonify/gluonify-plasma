package io.gluonify.plasma.release;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Release trust, corner by corner: manifests that cannot be read, public keys and key files, key announcements, signatures and files that do not match. */
class ReleaseTrustGapsTest {
    static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    static final String SHA = "ab".repeat(32);
    static final Duration RETIRE_AFTER = Duration.ofDays(90);

    @TempDir
    Path dir;

    static KeyPair gen() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    static String der(final KeyPair k) {
        return Base64.getEncoder().encodeToString(k.getPublic().getEncoded());
    }

    static ReleaseManifest.Artifact artifact(final String name, final String arch, final String file) {
        return new ReleaseManifest.Artifact(name, arch, file, SHA, 100);
    }

    static ReleaseManifest manifest(final List<ReleaseManifest.Artifact> artifacts) {
        return new ReleaseManifest(1, "1.2.3", 5, NOW.toString(), NOW.plusSeconds(86_400).toString(), artifacts);
    }

    static ReleaseManifest good() {
        return manifest(List.of(artifact("gluonify-strange", "amd64", "gluonify-strange-linux-amd64")));
    }

    static String refusal(final org.junit.jupiter.api.function.Executable call) {
        return assertThrows(ReleaseManifest.Invalid.class, call).getMessage();
    }

    // --- manifest structure ---

    @Test
    void aManifestHeaderIsValidatedFieldByField() {
        assertEquals(good(), good().validate());
        final ReleaseManifest g = good();
        assertTrue(refusal(() -> new ReleaseManifest(2, "1.2.3", 5, NOW.toString(), NOW.toString(), g.artifacts()).validate()).contains("unsupported manifest schema"));
        assertTrue(refusal(() -> new ReleaseManifest(1, null, 5, NOW.toString(), NOW.toString(), g.artifacts()).validate()).contains("invalid version"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "latest", 5, NOW.toString(), NOW.toString(), g.artifacts()).validate()).contains("invalid version"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 0, NOW.toString(), NOW.toString(), g.artifacts()).validate()).contains("invalid sequence"));
    }

    @Test
    void manifestDatesAndArtifactListAreValidated() {
        final ReleaseManifest g = good();
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, null, NOW.toString(), g.artifacts()).validate()).contains("dates absentes"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, NOW.toString(), null, g.artifacts()).validate()).contains("dates absentes"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, "yesterday", NOW.toString(), g.artifacts()).validate()).contains("invalid date"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, NOW.toString(), "tomorrow", g.artifacts()).validate()).contains("invalid date"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, NOW.toString(), NOW.toString(), null).validate()).contains("invalid artifact list"));
        assertTrue(refusal(() -> new ReleaseManifest(1, "1.2.3", 5, NOW.toString(), NOW.toString(), List.of()).validate()).contains("invalid artifact list"));
    }

    @Test
    void tooManyArtifactsAreRefused() {
        final java.util.ArrayList<ReleaseManifest.Artifact> many = new java.util.ArrayList<>();
        for (int i = 0; i <= ReleaseManifest.MAX_ARTIFACTS; i++) {
            many.add(artifact("gluonify-strange", "amd64", "f" + i));
        }
        assertTrue(refusal(() -> manifest(many).validate(n -> true)).contains("invalid artifact list"));
    }

    @Test
    void anArtifactNamesAKnownComponentAndArchitecture() {
        assertTrue(refusal(() -> manifest(java.util.Arrays.asList((ReleaseManifest.Artifact) null)).validate()).contains("unknown component: null"));
        assertTrue(refusal(() -> manifest(List.of(artifact(null, "amd64", "f"))).validate()).contains("unknown component"));
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-unknown", "amd64", "f"))).validate()).contains("unknown component: gluonify-unknown"));
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", null, "f"))).validate()).contains("invalid architecture"));
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", "sparc", "f"))).validate()).contains("invalid architecture: sparc"));
    }

    @Test
    void anArtifactHasASafeFileNameADigestAndASize() {
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", "amd64", null))).validate()).contains("invalid file name"));
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", "amd64", "../etc/passwd"))).validate()).contains("invalid file name"));
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", "amd64", "a..b"))).validate()).contains("invalid file name"));
        assertTrue(refusal(() -> manifest(List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", null, 1))).validate()).contains("invalid digest"));
        assertTrue(refusal(() -> manifest(List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", "XYZ", 1))).validate()).contains("invalid digest"));
        assertTrue(refusal(() -> manifest(List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", SHA, 0))).validate()).contains("invalid size"));
        assertTrue(refusal(() -> manifest(List.of(new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", SHA, ReleaseManifest.MAX_ARTIFACT_BYTES + 1))).validate()).contains("invalid size"));
    }

    @Test
    void duplicateArtifactsAreRefused() {
        assertTrue(refusal(() -> manifest(List.of(artifact("gluonify-strange", "amd64", "a"), artifact("gluonify-strange", "amd64", "b"))).validate()).contains("artefact en double"));
        assertEquals(2, manifest(List.of(artifact("gluonify-strange", "amd64", "a"), artifact("gluonify-strange", "arm64", "b"))).validate().artifacts().size());
        assertEquals(1, manifest(List.of(artifact("other-store-name", "amd64", "a"))).validate(n -> ReleaseManifest.SAFE_NAME.matcher(n).matches()).artifacts().size());
    }

    @Test
    void anArtifactKnowsItsGzipForm() {
        assertTrue(artifact("gluonify-strange", "amd64", "x-linux-amd64.gz").gzipped());
        assertEquals("x-linux-amd64", artifact("gluonify-strange", "amd64", "x-linux-amd64.gz").plainFile());
        assertFalse(artifact("gluonify-node-image", "all", "image.tar.gz").gzipped());
        assertEquals("image.tar.gz", artifact("gluonify-node-image", "all", "image.tar.gz").plainFile());
        assertEquals("gluonify-strange-linux-amd64", good().find("gluonify-strange", "amd64").orElseThrow().file());
        assertTrue(good().find("gluonify-strange", "arm64").isEmpty());
        assertTrue(manifest(List.of(artifact("gluonify-node-image", "all", "i.tar.gz"))).find("gluonify-node-image", "arm64").isPresent(), "arch all fits every architecture");
    }

    @Test
    void parsingNeedsTheExactCanonicalText() {
        final String text = good().toJson();
        assertEquals(good().version(), ReleaseManifest.parse(text.getBytes(StandardCharsets.UTF_8)).version());
        assertTrue(refusal(() -> ReleaseManifest.parse(new byte[ReleaseManifest.MAX_MANIFEST_BYTES + 1])).contains("too large"));
        assertTrue(refusal(() -> ReleaseManifest.parse("{}".getBytes(StandardCharsets.UTF_8))).contains("unreadable manifest"));
        assertTrue(refusal(() -> ReleaseManifest.parse(text.substring(0, text.length() - 1).getBytes(StandardCharsets.UTF_8))).contains("unreadable manifest"));
        assertTrue(refusal(() -> ReleaseManifest.parse(("x" + text).getBytes(StandardCharsets.UTF_8))).contains("unreadable manifest"));
        final String noNewline = text.replace("\n]\n}\n", "]\n}\n");
        assertTrue(refusal(() -> ReleaseManifest.parse(noNewline.getBytes(StandardCharsets.UTF_8))).contains("unreadable"));
        final String badLine = text.replace("{\"name\"", "{\"nom\"");
        assertTrue(refusal(() -> ReleaseManifest.parse(badLine.getBytes(StandardCharsets.UTF_8))).contains("unreadable artifact line"));
        final String empty = text.substring(0, text.indexOf("\"artifacts\":[\n") + "\"artifacts\":[\n".length()) + "]\n}\n";
        assertTrue(refusal(() -> ReleaseManifest.parse(empty.getBytes(StandardCharsets.UTF_8))).contains("invalid artifact list"));
        final String reordered = text.replace("\"sequence\":5,\n", "\"sequence\":05,\n");
        assertTrue(refusal(() -> ReleaseManifest.parse(reordered.getBytes(StandardCharsets.UTF_8))).contains("non canonique"));
    }

    // --- public keys ---

    @Test
    void aPublicKeyIsReadFromDerOrPemOrRefused() throws Exception {
        final KeyPair k = gen();
        final TrustedKeys.Key key = TrustedKeys.Key.ofDer(der(k));
        assertEquals(k.getPublic(), key.publicKey());
        assertEquals(key, TrustedKeys.Key.ofPem(key.pem()));
        assertTrue(key.pem().startsWith("-----BEGIN PUBLIC KEY-----\n") && key.pem().endsWith("-----END PUBLIC KEY-----\n"));
        assertEquals(k.getPublic(), ReleaseVerifier.publicKey(key.pem()));
        assertTrue(refusal(() -> TrustedKeys.Key.ofDer(null)).contains("unreadable public key"));
        assertTrue(refusal(() -> TrustedKeys.Key.ofDer("short")).contains("unreadable public key"));
        assertTrue(refusal(() -> TrustedKeys.Key.ofDer("A".repeat(60))).contains("unreadable public key"), "well-formed Base64 that is no key");
        assertTrue(refusal(() -> ReleaseVerifier.publicKey("not a pem")).contains("unreadable public key"));
        assertTrue(refusal(() -> ReleaseVerifier.publicKey("-----BEGIN PUBLIC KEY-----\n!!!\n-----END PUBLIC KEY-----")).contains("unreadable public key"));
    }

    @Test
    void trustedKeysNeedACurrentKeyAndAConsistentOverlap() throws Exception {
        final TrustedKeys.Key a = TrustedKeys.Key.ofDer(der(gen()));
        final TrustedKeys.Key b = TrustedKeys.Key.ofDer(der(gen()));
        assertTrue(refusal(() -> new TrustedKeys(null, null, null, 0)).contains("no trusted key"));
        assertTrue(refusal(() -> new TrustedKeys(a, b, null, 0)).contains("inconsistent overlap"));
        assertTrue(refusal(() -> new TrustedKeys(a, null, NOW, 0)).contains("inconsistent overlap"));
        assertEquals(List.of(a), TrustedKeys.single(a).accepted(NOW));
        assertEquals(List.of(a, b), new TrustedKeys(a, b, NOW.plusSeconds(10), 3).accepted(NOW));
        assertEquals(List.of(b), new TrustedKeys(a, b, NOW.plusSeconds(10), 3).accepted(NOW.plusSeconds(10)));
    }

    @Test
    void theKeysFileRoundTripsAndEveryDeviationIsRefused() throws Exception {
        final TrustedKeys.Key a = TrustedKeys.Key.ofDer(der(gen()));
        final TrustedKeys.Key b = TrustedKeys.Key.ofDer(der(gen()));
        final TrustedKeys single = TrustedKeys.single(a);
        assertEquals(single, TrustedKeys.parse(single.toText()));
        final TrustedKeys overlap = new TrustedKeys(a, b, NOW, 4);
        assertEquals(overlap, TrustedKeys.parse(overlap.toText()));
        final String text = overlap.toText();
        for (final String bad : new String[]{"", "gluonify-release-keys 2\nsequence 1\ncurrent x y\n", "gluonify-release-keys 1\nsequence x\ncurrent a b\n",
                text.replace("current ", "curent "), text.replace(a.sha256(), "XYZ"), text.replace(a.sha256(), "00".repeat(32)), text + "extra\n", text.replace("\nnext ", "\nnext x ").replace(b.sha256() + " ", ""),
                text.substring(0, text.length() - 1), text.replace(NOW.toString(), "tomorrow")}) {
            assertThrows(ReleaseManifest.Invalid.class, () -> TrustedKeys.parse(bad), bad);
        }
        assertTrue(refusal(() -> TrustedKeys.parse(text.replace(a.sha256(), "00".repeat(32)))).contains("inconsistent digest"));
        assertTrue(refusal(() -> TrustedKeys.parse(text.replace(a.sha256(), "XYZ"))).contains("invalid digest"));
        assertTrue(refusal(() -> TrustedKeys.parse(text.replace(NOW.toString(), "tomorrow"))).contains("unreadable date"));
        assertTrue(refusal(() -> TrustedKeys.parse(text + "extra\n")).contains("unexpected content"));
    }

    @Test
    void signedAndAnnouncementValuesCompareByContent() {
        final TrustedKeys.Signed s = new TrustedKeys.Signed(new byte[]{1}, new byte[]{2}, null);
        assertEquals(s, new TrustedKeys.Signed(new byte[]{1}, new byte[]{2}, null));
        assertEquals(s.hashCode(), new TrustedKeys.Signed(new byte[]{1}, new byte[]{2}, null).hashCode());
        assertNotEquals(s, new TrustedKeys.Signed(new byte[]{1}, new byte[]{3}, null));
        assertNotEquals(s, new TrustedKeys.Signed(new byte[]{9}, new byte[]{2}, null));
        assertNotEquals(s, new TrustedKeys.Signed(new byte[]{1}, new byte[]{2}, new byte[]{0}));
        final Object other = "x";
        assertNotEquals(s, other);
        assertTrue(s.toString().startsWith("Signed[manifest=[1]"));
        final TrustedKeys.Announcement a = new TrustedKeys.Announcement(new byte[]{1}, new byte[]{2}, new byte[]{3});
        assertEquals(a, new TrustedKeys.Announcement(new byte[]{1}, new byte[]{2}, new byte[]{3}));
        assertEquals(a.hashCode(), new TrustedKeys.Announcement(new byte[]{1}, new byte[]{2}, new byte[]{3}).hashCode());
        assertNotEquals(a, new TrustedKeys.Announcement(new byte[]{1}, new byte[]{2}, new byte[]{4}));
        assertNotEquals(a, new TrustedKeys.Announcement(new byte[]{1}, new byte[]{9}, new byte[]{3}));
        assertNotEquals(a, new TrustedKeys.Announcement(new byte[]{9}, new byte[]{2}, new byte[]{3}));
        final Object none = null;
        assertNotEquals(a, none, "never equal to null");
        assertTrue(a.toString().startsWith("Announcement[rollover=[1]"));
    }

    // --- verification with a rotation in progress ---

    @Test
    void aManifestNobodyTrustedSignedIsRefusedWhateverTheState() throws Exception {
        final KeyPair o = gen();
        final KeyPair n = gen();
        final KeyPair stranger = gen();
        final TrustedKeys.Key old = TrustedKeys.Key.ofDer(der(o));
        final byte[] m = good().toJson().getBytes(StandardCharsets.UTF_8);
        final TrustedKeys.Signed bad = new TrustedKeys.Signed(m, ReleaseSigner.sign(m, stranger.getPrivate()), ReleaseSigner.sign(m, stranger.getPrivate()));
        assertTrue(refusal(() -> TrustedKeys.single(old).verify(bad, ReleaseManifest::parse, 0, NOW)).contains("invalid manifest signature"));
        final TrustedKeys.Signed none = new TrustedKeys.Signed(m, null, null);
        assertTrue(refusal(() -> TrustedKeys.single(old).verify(none, ReleaseManifest::parse, 0, NOW)).contains("invalid manifest signature"));
        final TrustedKeys rotating = new TrustedKeys(old, TrustedKeys.Key.ofDer(der(n)), NOW.plusSeconds(100), 2);
        assertTrue(refusal(() -> rotating.verify(none, ReleaseManifest::parse, 0, NOW.plusSeconds(500))).contains("invalid manifest signature"));
        final TrustedKeys.Signed onlyOld = new TrustedKeys.Signed(m, ReleaseSigner.sign(m, o.getPrivate()), null);
        assertTrue(refusal(() -> rotating.verify(onlyOld, ReleaseManifest::parse, 0, NOW.plusSeconds(500))).contains("publication key retired"));
        assertEquals(old, rotating.verify(onlyOld, ReleaseManifest::parse, 0, NOW).signer(), "before the switch the old key still signs");
        final TrustedKeys.Signed second = new TrustedKeys.Signed(m, ReleaseSigner.sign(m, stranger.getPrivate()), ReleaseSigner.sign(m, n.getPrivate()));
        assertTrue(rotating.verify(second, ReleaseManifest::parse, 0, NOW).viaSig2(), "the countersignature by the new key carries it");
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedLongFormsStillVerifyAndApply() throws Exception {
        final KeyPair o = gen();
        final byte[] m = good().toJson().getBytes(StandardCharsets.UTF_8);
        final TrustedKeys keys = TrustedKeys.single(TrustedKeys.Key.ofDer(der(o)));
        assertEquals(5, keys.verify(m, ReleaseSigner.sign(m, o.getPrivate()), null, ReleaseManifest::parse, 0, NOW).manifest().sequence());
        assertEquals(5, ReleaseVerifier.verify(m, ReleaseSigner.sign(m, o.getPrivate()), o.getPublic(), ReleaseVerifier.Policy.of(ReleaseManifest::parse, 0, NOW)).sequence());
        assertEquals(5, ReleaseVerifier.verify(m, ReleaseSigner.sign(m, o.getPrivate()), o.getPublic(), new ReleaseVerifier.Policy(ReleaseManifest::parse, 0, NOW, n -> true)).sequence());
        assertTrue(refusal(() -> keys.applyRollover(m, null, null, null, null, null, 0, NOW)).contains("incomplete key announcement"));
        assertFalse(keys.finish(m, ReleaseSigner.sign(m, o.getPrivate()), null, 0, NOW).changed(), "no next key: nothing to finish");
    }

    // --- the announcement ---

    static KeyRollover announcement(final KeyPair next, final Instant created, final Instant retire) {
        final TrustedKeys.Key k = TrustedKeys.Key.ofDer(der(next));
        return new KeyRollover(1, "1.2.3", 5, SHA, created.toString(), k.der(), k.sha256(), retire.toString());
    }

    @Test
    void aKeyAnnouncementHeaderIsValidatedFieldByField() throws Exception {
        final KeyPair n = gen();
        final Instant retire = NOW.plus(RETIRE_AFTER);
        final KeyRollover ok = announcement(n, NOW, retire);
        assertSame(ok, ok.validate());
        assertEquals(retire, ok.retireAt());
        assertEquals(TrustedKeys.Key.ofDer(der(n)), ok.key());
        final String k = ok.nextKey();
        final String s = ok.nextKeySha256();
        assertTrue(refusal(() -> new KeyRollover(2, "1.2.3", 5, SHA, NOW.toString(), k, s, retire.toString()).validate()).contains("unsupported key announcement schema"));
        assertTrue(refusal(() -> new KeyRollover(1, null, 5, SHA, NOW.toString(), k, s, retire.toString()).validate()).contains("invalid version"));
        assertTrue(refusal(() -> new KeyRollover(1, "x", 5, SHA, NOW.toString(), k, s, retire.toString()).validate()).contains("invalid version"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 0, SHA, NOW.toString(), k, s, retire.toString()).validate()).contains("invalid sequence"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, null, NOW.toString(), k, s, retire.toString()).validate()).contains("invalid manifest digest"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, "XYZ", NOW.toString(), k, s, retire.toString()).validate()).contains("invalid manifest digest"));
    }

    @Test
    void aKeyAnnouncementDatesAreValidated() throws Exception {
        final KeyRollover ok = announcement(gen(), NOW, NOW.plus(RETIRE_AFTER));
        final String k = ok.nextKey();
        final String s = ok.nextKeySha256();
        final Instant retire = ok.retireAt();
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, "later", k, s, retire.toString()).validate()).contains("invalid date"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), k, s, null).validate()).contains("invalid date"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), k, s, NOW.toString()).validate()).contains("out of range"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), k, s, NOW.plus(Duration.ofDays(401)).toString()).validate()).contains("out of range"));
    }

    @Test
    void aKeyAnnouncementKeyMatchesItsDigest() throws Exception {
        final KeyRollover ok = announcement(gen(), NOW, NOW.plus(RETIRE_AFTER));
        final String k = ok.nextKey();
        final String s = ok.nextKeySha256();
        final Instant retire = ok.retireAt();
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), null, s, retire.toString()).validate()).contains("invalid key"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), "not base64!", s, retire.toString()).validate()).contains("invalid key"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), k, null, retire.toString()).validate()).contains("digest does not match"));
        assertTrue(refusal(() -> new KeyRollover(1, "1.2.3", 5, SHA, NOW.toString(), k, SHA, retire.toString()).validate()).contains("digest does not match"));
    }

    @Test
    void parsingAKeyAnnouncementNeedsItsExactCanonicalText() throws Exception {
        final KeyRollover ok = announcement(gen(), NOW, NOW.plus(Duration.ofDays(30)));
        assertEquals(ok, KeyRollover.parse(ok.toJson().getBytes(StandardCharsets.UTF_8)));
        assertTrue(refusal(() -> KeyRollover.parse(new byte[KeyRollover.MAX_BYTES + 1])).contains("too large"));
        assertTrue(refusal(() -> KeyRollover.parse("{}".getBytes(StandardCharsets.UTF_8))).contains("unreadable key announcement"));
        assertTrue(refusal(() -> KeyRollover.parse(ok.toJson().replace("\"sequence\":5", "\"sequence\":05").getBytes(StandardCharsets.UTF_8))).contains("non-canonical"));
        assertTrue(refusal(() -> KeyRollover.parse(ok.toJson().replace("\"schema\":1", "\"schema\":9").getBytes(StandardCharsets.UTF_8))).contains("unsupported key announcement schema"));
    }

    // --- the signer and the files ---

    @Test
    void theSignerBuildsAManifestFromADirectory() throws Exception {
        Files.writeString(dir.resolve("gluonify-strange-linux-amd64"), "x");
        Files.writeString(dir.resolve("gluonify-strange-linux-arm64.gz"), "yy");
        Files.writeString(dir.resolve("gluonify-higgs-linux-arm64"), "zzz");
        Files.writeString(dir.resolve("gluonify-higgs-linux-amd64.gz"), "w");
        Files.writeString(dir.resolve("gluonify-node-image.tar.gz"), "i");
        Files.writeString(dir.resolve("unrelated.txt"), "ignored");
        final ReleaseManifest m = ReleaseSigner.build(dir, "1.0.0", 3, NOW, NOW.plusSeconds(600));
        assertEquals(5, m.artifacts().size());
        assertEquals(List.of("all", "amd64", "amd64", "arm64", "arm64"), m.artifacts().stream().map(ReleaseManifest.Artifact::arch).sorted().toList());
        assertTrue(m.artifacts().stream().anyMatch(a -> a.name().equals("gluonify-strange") && a.arch().equals("arm64") && a.gzipped()));
        assertTrue(m.artifacts().stream().anyMatch(a -> a.name().equals("gluonify-node-image") && a.arch().equals("all")));
    }

    @Test
    void aDownloadedFileMustHaveTheDeclaredSizeAndDigest() throws Exception {
        final Path f = Files.writeString(dir.resolve("f"), "hello");
        final String sha = ReleaseVerifier.sha256(f);
        ReleaseVerifier.checkFile(f, new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", sha, 5));
        assertTrue(refusal(() -> ReleaseVerifier.checkFile(f, new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", sha, 6))).contains("unexpected size"));
        assertTrue(refusal(() -> ReleaseVerifier.checkFile(f, new ReleaseManifest.Artifact("gluonify-strange", "amd64", "f", "00".repeat(32), 5))).contains("invalid SHA-256"));
        assertEquals(ReleaseVerifier.sha256("hello".getBytes(StandardCharsets.UTF_8)), sha);
    }

    @Test
    void signaturesAreRawOrBase64AndNothingElse() throws Exception {
        final KeyPair k = gen();
        final byte[] m = good().toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] raw = ReleaseSigner.sign(m, k.getPrivate());
        assertTrue(ReleaseVerifier.signatureValid(m, raw, k.getPublic()));
        assertTrue(ReleaseVerifier.signatureValid(m, ReleaseSigner.signBase64(m, k.getPrivate()).getBytes(StandardCharsets.US_ASCII), k.getPublic()));
        assertTrue(refusal(() -> ReleaseVerifier.signatureValid(m, new byte[10], k.getPublic())).contains("unreadable signature"));
        assertTrue(refusal(() -> ReleaseVerifier.signatureValid(m, "!!! not base64 !!!".getBytes(StandardCharsets.US_ASCII), k.getPublic())).contains("unreadable signature"));
        assertTrue(refusal(() -> ReleaseVerifier.signatureValid(m, Base64.getEncoder().encode(new byte[10]), k.getPublic())).contains("unreadable signature"));
        assertFalse(ReleaseVerifier.signatureValid(m, raw, gen().getPublic()));
        assertFalse(ReleaseVerifier.signatureValid("other".getBytes(StandardCharsets.UTF_8), raw, k.getPublic()));
    }

    @Test
    void aManifestIsRefusedWhenExpiredOrBelowTheRequiredSequence() throws Exception {
        final KeyPair k = gen();
        final byte[] m = good().toJson().getBytes(StandardCharsets.UTF_8);
        final byte[] sig = ReleaseSigner.sign(m, k.getPrivate());
        final ReleaseVerifier.Policy policy = ReleaseVerifier.Policy.of(ReleaseManifest::parse, 5, NOW);
        assertEquals(5, ReleaseVerifier.verify(m, sig, k.getPublic(), policy).sequence());
        assertTrue(refusal(() -> ReleaseVerifier.verify(m, sig, k.getPublic(), ReleaseVerifier.Policy.of(ReleaseManifest::parse, 6, NOW))).contains("rollback refused"));
        assertTrue(refusal(() -> ReleaseVerifier.verify(m, sig, k.getPublic(), ReleaseVerifier.Policy.of(ReleaseManifest::parse, 0, NOW.plusSeconds(86_401)))).contains("manifest expired"));
        assertTrue(refusal(() -> ReleaseVerifier.verify(m, ReleaseSigner.sign(m, gen().getPrivate()), k.getPublic(), policy)).contains("invalid manifest signature"));
    }
}
