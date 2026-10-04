package io.gluonify.plasma.wg;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gestion de l'interface WireGuard d'une machine (noyau Linux, outils {@code ip} et {@code wg} de wireguard-tools) : clé locale, interface, adresse, pairs. Idempotent :
 * {@link #apply} ramène l'interface à l'état voulu (pairs ajoutés, mis à jour, retirés) sans la recréer. Les commandes sont construites par des fonctions pures (testées).
 */
public final class WireGuard {
    /** Un pair du maillage : clé publique, point d'accès sous-jacent (hôte:port UDP) et adresse dans le maillage. */
    public record Peer(String id, String publicKey, String endpoint, String overlay) {}

    private final Path keyFile;

    public WireGuard(Path keyFile) {
        this.keyFile = keyFile;
    }

    /** Clé publique de cette machine ; la clé privée est créée au premier appel (0600) et ne quitte jamais le fichier. */
    public synchronized String publicKey() throws IOException, InterruptedException {
        if (!Files.exists(keyFile)) {
            Files.createDirectories(keyFile.toAbsolutePath().getParent());
            String priv = run(List.of("wg", "genkey")).strip();
            Path tmp = Files.createTempFile(keyFile.toAbsolutePath().getParent(), "wg-", ".tmp", java.nio.file.attribute.PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            Files.writeString(tmp, priv + "\n", StandardCharsets.UTF_8);
            Files.move(tmp, keyFile, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        }
        Process p = new ProcessBuilder("wg", "pubkey").redirectErrorStream(true).start();
        p.getOutputStream().write(Files.readString(keyFile, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        if (p.waitFor() != 0 || !Overlay.validKey(out)) throw new IOException("wg pubkey : " + out);
        return out;
    }

    /** Amène l'interface à : adresse {@code self}, port d'écoute, et exactement ces pairs. */
    public synchronized void apply(String self, int port, List<Peer> peers) throws IOException, InterruptedException {
        if (exec(List.of("ip", "link", "show", Overlay.INTERFACE)) != 0) {
            for (List<String> c : createCommands(self, keyFile.toString(), port)) run(c);
        }
        Set<String> have = new HashSet<>(List.of(run(List.of("wg", "show", Overlay.INTERFACE, "peers")).split("\\s+")));
        Set<String> want = new HashSet<>();
        for (Peer p : peers) {
            if (!Overlay.validKey(p.publicKey()) || p.overlay() == null) continue;
            want.add(p.publicKey());
            run(peerCommand(p));
        }
        for (String k : have) if (!k.isBlank() && !want.contains(k)) run(List.of("wg", "set", Overlay.INTERFACE, "peer", k, "remove"));
    }

    // ---------- fonctions pures (testées)

    static List<List<String>> createCommands(String self, String keyFile, int port) {
        return List.of(
                List.of("ip", "link", "add", Overlay.INTERFACE, "type", "wireguard"),
                List.of("wg", "set", Overlay.INTERFACE, "private-key", keyFile, "listen-port", String.valueOf(port)),
                List.of("ip", "addr", "add", self + "/16", "dev", Overlay.INTERFACE),
                List.of("ip", "link", "set", Overlay.INTERFACE, "mtu", "1380", "up"));
    }

    /** Un pair : seule son adresse dans le maillage est routée vers lui ({@code allowed-ips} en /32), keepalive pour traverser les NAT. */
    static List<String> peerCommand(Peer p) {
        List<String> c = new ArrayList<>(List.of("wg", "set", Overlay.INTERFACE, "peer", p.publicKey(), "allowed-ips", p.overlay() + "/32", "persistent-keepalive", "25"));
        if (p.endpoint() != null && !p.endpoint().isBlank()) {
            c.add("endpoint");
            c.add(p.endpoint());
        }
        return c;
    }

    private static String run(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd).replaceAll("private-key \\S+", "private-key …") + " : " + out.trim());
        return out;
    }

    private static int exec(List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }
}
