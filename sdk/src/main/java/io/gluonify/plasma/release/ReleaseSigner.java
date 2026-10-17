package io.gluonify.plasma.release;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Production of a manifest from a directory of binaries ({@code <component>-linux-<arch>}, {@code <component>-linux-<arch>.gz} (gzip, see {@link Gzip}) or {@code <component>.tar.gz}) and signing. The private key is read only at
 * run time, from a given path: it is never in a repository. Same format as bin/sign-release.sh (which is used in production, with openssl).
 */
public final class ReleaseSigner {
    private ReleaseSigner() {}

    /** Parcourt {@code dir} : gluonify-strange-linux-amd64, gluonify-higgs-linux-arm64, gluonify-node-image.tar.gz (arch all)… */
    public static ReleaseManifest build(Path dir, String version, long sequence, Instant createdAt, Instant expires) throws java.io.IOException {
        List<ReleaseManifest.Artifact> out = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted(Comparator.comparing(Path::getFileName)).toList()) {
                String n = f.getFileName().toString();
                for (String name : ReleaseManifest.NAMES.stream().sorted().toList()) {
                    String arch = null;
                    if (n.equals(name + "-linux-amd64")) arch = "amd64";
                    else if (n.equals(name + "-linux-arm64")) arch = "arm64";
                    else if (n.equals(name + "-linux-amd64.gz")) arch = "amd64";
                    else if (n.equals(name + "-linux-arm64.gz")) arch = "arm64";
                    else if (n.equals(name + ".tar.gz")) arch = "all";
                    if (arch != null) out.add(new ReleaseManifest.Artifact(name, arch, n, ReleaseVerifier.sha256(f), Files.size(f)));
                }
            }
        }
        return new ReleaseManifest(ReleaseManifest.SCHEMA, version, sequence, createdAt.toString(), expires.toString(), out).validate();
    }

    /** Raw Ed25519 signature (64 bytes) of the manifest bytes. */
    public static byte[] sign(byte[] manifest, PrivateKey key) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(manifest);
            return s.sign();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String signBase64(byte[] manifest, PrivateKey key) {
        return Base64.getEncoder().encodeToString(sign(manifest, key)) + "\n";
    }

    /** Key change announcement bound to {@code manifest} (signed bytes); to be signed with the current key then countersigned with the new one. */
    public static KeyRollover rollover(byte[] manifestBytes, ReleaseManifest manifest, java.security.PublicKey nextKey, Instant createdAt, Instant retireOldAfter) {
        TrustedKeys.Key k = TrustedKeys.Key.ofDer(Base64.getEncoder().encodeToString(nextKey.getEncoded()));
        return new KeyRollover(KeyRollover.SCHEMA, manifest.version(), manifest.sequence(), ReleaseVerifier.sha256(manifestBytes), createdAt.toString(), k.der(), k.sha256(), retireOldAfter.toString()).validate();
    }
}
