package io.gluonify.plasma.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Rendezvous-hash placement: for a key (the sha256 of an object), the storage nodes are ranked by the hash of "key|node". Everyone who knows
 * the same list of nodes gets the same ranking, without a directory; when a node arrives or leaves, only the objects that concern it move.
 */
public final class Rendezvous {
    private Rendezvous() {}

    /** All the nodes, from first choice to last. */
    public static List<String> order(final String key, final Collection<String> nodes) {
        List<String> out = new ArrayList<>(new java.util.TreeSet<>(nodes)); // deduplicated, input order has no effect
        out.sort(Comparator.comparing((String n) -> score(key, n)).reversed().thenComparing(Comparator.naturalOrder()));
        return out;
    }

    /** The first {@code k} choices (all of them if there are fewer than {@code k} nodes). */
    public static List<String> pick(final String key, final Collection<String> nodes, final int k) {
        List<String> o = order(key, nodes);
        return o.subList(0, Math.min(Math.max(k, 0), o.size()));
    }

    private static String score(final String key, final String node) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((key + "|" + node).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
