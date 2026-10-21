package io.gluonify.plasma.wg;

/** Overlay network (WireGuard): a private subnet common to all nodes and control-planes, one address per machine (assigned deterministically by the control-plane). */
public final class Overlay {
    private Overlay() {}

    /** Mesh subnet: must overlap neither the applications' (10.200.0.0/16) nor the machines' networks. */
    public static final String SUBNET = "10.77.0.0/16";
    public static final int PORT = 51820;
    public static final String INTERFACE = "wg0";

    /** Address no. {@code n} of the subnet (1 = first machine); IllegalStateException when it is full. */
    public static String address(final long n) {
        if (n < 1 || n > 65_533) throw new IllegalStateException("overlay network full (" + SUBNET + ")");
        return "10.77." + ((n >> 8) & 255) + "." + (n & 255);
    }

    /** True if {@code ip} is in the mesh subnet (a connection coming from it came through the tunnel). */
    public static boolean contains(final String ip) {
        return ip != null && ip.startsWith("10.77.");
    }

    /** WireGuard public key: 32 bytes in base64 (44 characters). */
    public static boolean validKey(final String k) {
        return k != null && k.matches("[A-Za-z0-9+/]{43}=");
    }
}
