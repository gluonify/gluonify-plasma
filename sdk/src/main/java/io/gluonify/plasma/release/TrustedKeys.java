package io.gluonify.plasma.release;

import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Set of publication keys that a node (or sea) accepts, and rotation rules (see gluonify-codex/docs/BOOTSTRAP.md §11 ter).
 * <ul>
 *   <li>{@code current}: the trusted key; {@code next}: during the overlap, the key announced by a release signed by {@code current};
 *       {@code retireCurrentAfter}: from this date, {@code current} is no longer accepted (anti-rollback);</li>
 *   <li>{@code sequence}: sequence of the announcement that produced this state (0: original state, a single {@code release.pub} file); a new announcement must exceed it.</li>
 * </ul>
 * Immutable object: each step returns a new set, which the caller writes atomically.
 */
public record TrustedKeys(Key current, Key next, Instant retireCurrentAfter, long sequence) {
    /** @param sha256 SHA-256 digest of the DER (that of {@code --key-sha256}); @param der SubjectPublicKeyInfo in Base64. */
    public record Key(String sha256, String der) {
        static final Pattern B64 = Pattern.compile("[A-Za-z0-9+/]{40,120}={0,2}");

        public static Key ofDer(String b64) {
            if (b64 == null || !B64.matcher(b64).matches()) throw new ReleaseManifest.Invalid("unreadable public key");
            byte[] d;
            try {
                d = Base64.getDecoder().decode(b64);
                java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(d));
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                throw new ReleaseManifest.Invalid("unreadable public key");
            }
            return new Key(sha(d), b64);
        }

        public static Key ofPem(String pem) {
            PublicKey k = ReleaseVerifier.publicKey(pem);
            return ofDer(Base64.getEncoder().encodeToString(k.getEncoded()));
        }

        public PublicKey publicKey() {
            try {
                return java.security.KeyFactory.getInstance("Ed25519").generatePublic(new java.security.spec.X509EncodedKeySpec(Base64.getDecoder().decode(der)));
            } catch (java.security.GeneralSecurityException e) {
                throw new ReleaseManifest.Invalid("unreadable public key");
            }
        }

        public String pem() {
            return "-----BEGIN PUBLIC KEY-----\n" + der.replaceAll("(.{64})", "$1\n").trim() + "\n-----END PUBLIC KEY-----\n";
        }
    }

    static String sha(byte[] d) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(d));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final String HEADER = "gluonify-release-keys 1";
    private static final Pattern SHA_P = Pattern.compile("[0-9a-f]{64}");

    /** Original state: a single key (the {@code release.pub} file of a node installed before the rotation). */
    public static TrustedKeys single(Key k) {
        return new TrustedKeys(k, null, null, 0);
    }

    public TrustedKeys {
        if (current == null) throw new ReleaseManifest.Invalid("no trusted key");
        if ((next == null) != (retireCurrentAfter == null)) throw new ReleaseManifest.Invalid("keys: inconsistent overlap");
    }

    /** Keys accepted at {@code now}: {@code current} until the switch date, {@code next} during the overlap. */
    public List<Key> accepted(Instant now) {
        List<Key> out = new ArrayList<>();
        if (next == null || now.isBefore(retireCurrentAfter)) out.add(current);
        if (next != null) out.add(next);
        return out;
    }

    // ------------------------------------------------------------------ file

    public String toText() {
        StringBuilder sb = new StringBuilder(HEADER).append('\n').append("sequence ").append(sequence).append('\n');
        sb.append("current ").append(current.sha256()).append(' ').append(current.der()).append('\n');
        if (next != null) sb.append("next ").append(next.sha256()).append(' ').append(next.der()).append(' ').append(retireCurrentAfter).append('\n');
        return sb.toString();
    }

    /** Strict reading of the {@code release.keys} file; any unexpected line or inconsistent digest is refused. */
    public static TrustedKeys parse(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length < 4 || !lines[0].equals(HEADER)) throw new ReleaseManifest.Invalid("unreadable keys file");
        if (!lines[1].matches("sequence [0-9]{1,15}")) throw new ReleaseManifest.Invalid("keys file: unreadable sequence");
        long seq = Long.parseLong(lines[1].substring(9));
        Key cur = null, nxt = null;
        Instant retire = null;
        int i = 2;
        String[] p = lines[i++].split(" ");
        if (p.length != 3 || !p[0].equals("current")) throw new ReleaseManifest.Invalid("keys file: unreadable current key");
        cur = checked(p[1], p[2]);
        if (i < lines.length && lines[i].startsWith("next ")) {
            p = lines[i++].split(" ");
            if (p.length != 4) throw new ReleaseManifest.Invalid("keys file: unreadable next key");
            nxt = checked(p[1], p[2]);
            try {
                retire = Instant.parse(p[3]);
            } catch (RuntimeException e) {
                throw new ReleaseManifest.Invalid("keys file: unreadable date");
            }
        }
        if (i != lines.length - 1 || !lines[i].isEmpty()) throw new ReleaseManifest.Invalid("keys file: unexpected content");
        return new TrustedKeys(cur, nxt, retire, seq);
    }

    private static Key checked(String sha, String der) {
        if (!SHA_P.matcher(sha).matches()) throw new ReleaseManifest.Invalid("keys file: invalid digest");
        Key k = Key.ofDer(der);
        if (!k.sha256().equals(sha)) throw new ReleaseManifest.Invalid("keys file: inconsistent digest");
        return k;
    }

    // ------------------------------------------------------------------ verification

    /** Verified manifest and the key that signed it. */
    public record Verified(ReleaseManifest manifest, Key signer, boolean viaSig2) {}

    /**
     * Verifies the manifest with the keys accepted at {@code now}: {@code sig} first, then {@code sig2} (countersignature by the new key, optional). Structure, expiration and
     * sequence ({@code >= minSequence}) as in {@link ReleaseVerifier#verify}. A manifest signed ONLY by the retired key is refused with an explicit message.
     */
    public Verified verify(byte[] manifest, byte[] sig, byte[] sig2, java.util.function.Function<byte[], ReleaseManifest> parser, long minSequence, Instant now) {
        List<Key> ok = accepted(now);
        for (int pass = 0; pass < 2; pass++) {
            byte[] s = pass == 0 ? sig : sig2;
            if (s == null) continue;
            for (Key k : ok) {
                boolean valid;
                try {
                    valid = ReleaseVerifier.signatureValid(manifest, s, k.publicKey());
                } catch (ReleaseManifest.Invalid e) {
                    valid = false;
                }
                if (valid) return new Verified(ReleaseVerifier.verify(manifest, s, k.publicKey(), parser, minSequence, now), k, pass == 1);
            }
        }
        if (next != null && !ok.contains(current)) {
            try {
                if (sig != null && ReleaseVerifier.signatureValid(manifest, sig, current.publicKey()))
                    throw new ReleaseManifest.Invalid("publication key retired since " + retireCurrentAfter + ": manifest signed only by the old key refused (rollback)");
            } catch (ReleaseManifest.Invalid e) {
                if (e.getMessage().startsWith("publication key retired")) throw e;
            }
        }
        throw new ReleaseManifest.Invalid("invalid manifest signature");
    }

    /** Result of an accepted announcement: new set, and {@code changed=false} if the state already announced this key (idempotence). */
    public record Applied(TrustedKeys keys, boolean changed, ReleaseManifest manifest) {}

    /**
     * Rules for accepting a key change (ALL required, otherwise {@link ReleaseManifest.Invalid}): (1) the manifest is valid (signature {@code sig}) under a key
     * currently accepted, not expired, sequence {@code >= highestSeen}; (2) the announcement is signed by THAT SAME key, bound to the manifest (digest, version, sequence);
     * (3) the new key countersigned the announcement ({@code rolloverSig2}) and, if present, the manifest ({@code sig2}); (4) the sequence exceeds that of the last key change;
     * (5) the new key differs from the signing key, the switch date is in the future; (6) no concurrent rotation: if a next key is already installed, it is the same one.
     */
    public Applied applyRollover(byte[] manifest, byte[] sig, byte[] sig2, byte[] rollover, byte[] rolloverSig, byte[] rolloverSig2, long highestSeen, Instant now) {
        if (sig == null || rollover == null || rolloverSig == null || rolloverSig2 == null) throw new ReleaseManifest.Invalid("incomplete key announcement (manifest, announcement and countersignature required)");
        Verified v = verify(manifest, sig, null, ReleaseManifest::parse, highestSeen, now); // only the main signature, never sig2, carries the announcement
        if (!ReleaseVerifier.signatureValid(rollover, rolloverSig, v.signer().publicKey())) throw new ReleaseManifest.Invalid("key announcement: invalid signature (not that of the key that signs the manifest)");
        KeyRollover r = KeyRollover.parse(rollover);
        if (!r.version().equals(v.manifest().version()) || r.sequence() != v.manifest().sequence()) throw new ReleaseManifest.Invalid("key announcement: version or sequence differs from the manifest");
        if (!r.manifestSha256().equals(sha(manifest))) throw new ReleaseManifest.Invalid("key announcement: not bound to this manifest");
        Key nk = r.key();
        if (nk.sha256().equals(v.signer().sha256())) throw new ReleaseManifest.Invalid("key announcement: the new key is the signing key");
        if (!ReleaseVerifier.signatureValid(rollover, rolloverSig2, nk.publicKey())) throw new ReleaseManifest.Invalid("key announcement: invalid countersignature by the new key");
        if (sig2 != null && !ReleaseVerifier.signatureValid(manifest, sig2, nk.publicKey())) throw new ReleaseManifest.Invalid("manifest: invalid countersignature by the new key");
        if (next != null && next.sha256().equals(nk.sha256())) return new Applied(this, false, v.manifest()); // already installed
        if (current.sha256().equals(nk.sha256())) return new Applied(this, false, v.manifest());
        if (next != null) throw new ReleaseManifest.Invalid("rotation already in progress towards another key (finish it first)");
        if (r.sequence() <= sequence) throw new ReleaseManifest.Invalid("key announcement replayed: sequence " + r.sequence() + " <= " + sequence + " (last key change)");
        if (!r.retireAt().isAfter(now)) throw new ReleaseManifest.Invalid("key announcement: the switch date has passed");
        return new Applied(new TrustedKeys(current, nk, r.retireAt(), r.sequence()), true, v.manifest());
    }

    /**
     * Finishes the rotation: the next key becomes the only key (the old one is retired). Requires a valid manifest signed by the NEXT key (proof that it works in practice),
     * not expired, sequence {@code >= highestSeen}.
     */
    public Applied finish(byte[] manifest, byte[] sig, byte[] sig2, long highestSeen, Instant now) {
        if (next == null) return new Applied(this, false, null); // nothing to finish: already the only key
        TrustedKeys onlyNext = new TrustedKeys(next, null, null, sequence);
        Verified v = onlyNext.verify(manifest, sig, sig2, ReleaseManifest::parse, highestSeen, now);
        return new Applied(new TrustedKeys(next, null, null, sequence), true, v.manifest());
    }
}
