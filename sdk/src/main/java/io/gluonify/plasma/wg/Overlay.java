package io.gluonify.plasma.wg;

/** Réseau superposé (WireGuard) : un sous-réseau privé commun à tous les nœuds et control-planes, une adresse par machine (attribuée de façon déterministe par le control-plane). */
public final class Overlay {
    private Overlay() {}

    /** Sous-réseau du maillage : ne doit chevaucher ni celui des applications (10.200.0.0/16) ni les réseaux des machines. */
    public static final String SUBNET = "10.77.0.0/16";
    public static final int PORT = 51820;
    public static final String INTERFACE = "wg0";

    /** Adresse n°{@code n} du sous-réseau (1 = première machine) ; IllegalStateException quand il est plein. */
    public static String address(long n) {
        if (n < 1 || n > 65_533) throw new IllegalStateException("réseau superposé plein (" + SUBNET + ")");
        return "10.77." + ((n >> 8) & 255) + "." + (n & 255);
    }

    /** Vrai si {@code ip} est dans le sous-réseau du maillage (une connexion qui en vient est venue par le tunnel). */
    public static boolean contains(String ip) {
        return ip != null && ip.startsWith("10.77.");
    }

    /** Clé publique WireGuard : 32 octets en base64 (44 caractères). */
    public static boolean validKey(String k) {
        return k != null && k.matches("[A-Za-z0-9+/]{43}=");
    }
}
