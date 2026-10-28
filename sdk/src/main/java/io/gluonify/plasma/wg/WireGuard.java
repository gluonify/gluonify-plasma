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
 * Management of a machine's WireGuard interface (Linux kernel, {@code ip} and {@code wg} tools of wireguard-tools): local key, interface, address, peers. Idempotent:
 * {@link #apply} brings the interface back to the desired state (peers added, updated, removed) without recreating it. The commands are built by pure functions (tested).
 */
public final class WireGuard {
    private static final String LINK = "link";
    private static final String SET = "set";
    /** A mesh peer: public key, underlying endpoint (host:UDP port) and address in the mesh. */
    public record Peer(String id, String publicKey, String endpoint, String overlay) {}

    private final Path keyFile;

    public WireGuard(final Path keyFile) {
        this.keyFile = keyFile;
    }

    /** Public key of this machine; the private key is created on the first call (0600) and never leaves the file. */
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
        if (p.waitFor() != 0 || !Overlay.validKey(out)) throw new IOException("wg pubkey: " + out);
        return out;
    }

    /** Brings the interface to: address {@code self}, listen port, and exactly these peers. */
    public synchronized void apply(final String self, final int port, final List<Peer> peers) throws IOException, InterruptedException {
        if (exec(List.of("ip", LINK, "show", Overlay.INTERFACE)) != 0) {
            for (List<String> c : createCommands(self, keyFile.toString(), port)) run(c);
        }
        Set<String> have = new HashSet<>(List.of(run(List.of("wg", "show", Overlay.INTERFACE, "peers")).split("\\s+")));
        Set<String> want = new HashSet<>();
        for (Peer p : peers) {
            if (!Overlay.validKey(p.publicKey()) || p.overlay() == null) continue;
            want.add(p.publicKey());
            run(peerCommand(p));
        }
        for (String k : have) if (!k.isBlank() && !want.contains(k)) run(List.of("wg", SET, Overlay.INTERFACE, "peer", k, "remove"));
    }

    // ---------- pure functions (tested)

    static List<List<String>> createCommands(final String self, final String keyFile, final int port) {
        return List.of(
                List.of("ip", LINK, "add", Overlay.INTERFACE, "type", "wireguard"),
                List.of("wg", SET, Overlay.INTERFACE, "private-key", keyFile, "listen-port", String.valueOf(port)),
                List.of("ip", "addr", "add", self + "/16", "dev", Overlay.INTERFACE),
                List.of("ip", LINK, SET, Overlay.INTERFACE, "mtu", "1380", "up"));
    }

    /** A peer: only its mesh address is routed to it ({@code allowed-ips} as /32), keepalive to traverse NATs. */
    static List<String> peerCommand(final Peer p) {
        List<String> c = new ArrayList<>(List.of("wg", SET, Overlay.INTERFACE, "peer", p.publicKey(), "allowed-ips", p.overlay() + "/32", "persistent-keepalive", "25"));
        if (p.endpoint() != null && !p.endpoint().isBlank()) {
            c.add("endpoint");
            c.add(p.endpoint());
        }
        return c;
    }

    private static String run(final List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        if (p.waitFor() != 0) throw new IOException(String.join(" ", cmd).replaceAll("private-key \\S+", "private-key …") + " : " + out.trim());
        return out;
    }

    private static int exec(final List<String> cmd) throws IOException, InterruptedException {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        return p.waitFor();
    }
}
