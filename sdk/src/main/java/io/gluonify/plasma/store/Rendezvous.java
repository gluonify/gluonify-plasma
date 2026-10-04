package io.gluonify.plasma.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Placement par hachage de rendez-vous : pour une clé (le sha256 d'un objet), les nœuds de stockage sont classés par le hash de « clé|nœud ». Tous ceux qui connaissent
 * la même liste de nœuds obtiennent le même classement, sans annuaire ; quand un nœud arrive ou part, seuls les objets qui le concernent changent de place.
 */
public final class Rendezvous {
    private Rendezvous() {}

    /** Tous les nœuds, du premier choix au dernier. */
    public static List<String> order(String key, Collection<String> nodes) {
        List<String> out = new ArrayList<>(new java.util.TreeSet<>(nodes)); // dédoublonné, ordre d'entrée sans effet
        out.sort(Comparator.comparing((String n) -> score(key, n)).reversed().thenComparing(Comparator.naturalOrder()));
        return out;
    }

    /** Les {@code k} premiers choix (tous s'il y a moins de {@code k} nœuds). */
    public static List<String> pick(String key, Collection<String> nodes, int k) {
        List<String> o = order(key, nodes);
        return o.subList(0, Math.min(Math.max(k, 0), o.size()));
    }

    private static String score(String key, String node) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((key + "|" + node).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
