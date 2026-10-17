package io.gluonify.plasma;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * System dependencies of an application artifact, per distribution: {@code {"debian13": ["libfreetype6", ...]}}.
 * The key is the distribution identifier from {@code /etc/os-release} ({@code ID} then {@code VERSION_ID}, without spaces: {@code debian13},
 * {@code ubuntu24.04}); this model is not tied to Debian. Package names are strictly validated: they are passed as is to the node's package
 * tool, which must never read them as options.
 */
public record Requires(Map<String, List<String>> packages) {
    public static final int MAX_PACKAGES = 64;
    public static final Requires NONE = new Requires(Map.of());
    private static final Pattern DISTRO = Pattern.compile("[a-z][a-z0-9]{0,31}([.][0-9]{1,8}){0,2}");
    private static final Pattern PACKAGE = Pattern.compile("[a-z0-9][a-z0-9+.-]{1,63}");

    public Requires {
        var sorted = new TreeMap<String, List<String>>();
        int total = 0;
        if (packages != null) for (var e : packages.entrySet()) {
            if (e.getKey() == null || !DISTRO.matcher(e.getKey()).matches()) throw new IllegalArgumentException("invalid distribution id: " + e.getKey());
            var names = new TreeSet<String>();
            if (e.getValue() != null) for (String p : e.getValue()) {
                if (p == null || !PACKAGE.matcher(p).matches()) throw new IllegalArgumentException("invalid package name: " + p);
                names.add(p);
            }
            total += names.size();
            if (total > MAX_PACKAGES) throw new IllegalArgumentException("too many packages (max " + MAX_PACKAGES + ")");
            if (!names.isEmpty()) sorted.put(e.getKey(), Collections.unmodifiableList(new ArrayList<>(names)));
        }
        packages = Collections.unmodifiableMap(sorted);
    }

    /** Packages to install for this distribution (empty if nothing is declared for it). */
    public List<String> forDistro(String distro) {
        return packages.getOrDefault(distro, List.of());
    }

    public boolean isEmpty() {
        return packages.isEmpty();
    }

    /** Distribution identifier from the {@code ID} and {@code VERSION_ID} keys of {@code /etc/os-release}: {@code debian} + {@code 13} = {@code debian13}. */
    public static String distroId(String id, String versionId) {
        String i = id == null ? "" : id.toLowerCase().replaceAll("[^a-z0-9]", "");
        String v = versionId == null ? "" : versionId.toLowerCase().replaceAll("[^a-z0-9.]", "");
        return i + v;
    }
}
