package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Json;
import io.gluonify.plasma.services.internal.SecureFiles;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The file {@code ~/.gluonify/config.json} (0600), written by {@code gluonify login <zone>}: the zones known to the workstation and the current zone.
 *
 * <pre>{@code
 * {
 *   "current": "gluonify.lan",
 *   "zones": {
 *     "gluonify.lan": {"discovery": "https://gluonify.lan/.well-known/gluonify", "environment": "SBX", "caFile": "/Users/me/pebble.pem"}
 *   }
 * }
 * }</pre>
 *
 * No token in this file: they are in {@code ~/.gluonify/tokens/<zone>.json} ({@link DeveloperTokens}).
 */
public final class DeveloperConfig {
    /** File name in the Gluonify folder. */
    public static final String FILE = "config.json";
    /** Environment variable: a folder other than {@code ~/.gluonify}. */
    public static final String ENV_HOME = "GLUONIFY_HOME";

    private static final Pattern ZONE = Pattern.compile("[a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?");

    /**
     * A known zone.
     *
     * @param name        name of the zone ({@code gluonify.lan})
     * @param discovery   address of the discovery document
     * @param environment environment announced at login ({@code SBX}), or {@code null}
     * @param caFile      certificate authority accepted in addition to those of the JDK (development cluster), or {@code null}
     */
    public record Zone(String name, URI discovery, String environment, Path caFile) {
    }

    private final String current;
    private final Map<String, Zone> zones;

    private DeveloperConfig(final String current, final Map<String, Zone> zones) {
        this.current = current;
        this.zones = Collections.unmodifiableMap(new LinkedHashMap<>(zones));
    }

    public static DeveloperConfig empty() {
        return new DeveloperConfig(null, Map.of());
    }

    /** The Gluonify folder: {@code GLUONIFY_HOME}, otherwise {@code <user.home>/.gluonify}. */
    public static Path home(final Function<String, String> environment, final Function<String, String> properties) {
        final String h = environment.apply(ENV_HOME);
        if (h != null && !h.isBlank()) {
            return Path.of(h.strip());
        }
        return Path.of(properties.apply("user.home"), ".gluonify");
    }

    /** @return true if {@code zone} is an accepted zone name (lower case, digits, dots and hyphens; no scheme, port or path) */
    public static boolean validZone(final String zone) {
        return zone != null && ZONE.matcher(zone).matches() && !zone.contains("..");
    }

    /** Reads the file; absent: an empty configuration. @throws GluonifyServicesException if the file is unreadable */
    public static DeveloperConfig read(final Path home) {
        final Path file = home.resolve(FILE);
        final String text;
        try {
            text = SecureFiles.read(file);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot read " + file + " (" + e.getClass().getSimpleName() + ")", e);
        }
        if (text == null) {
            return empty();
        }
        final Map<String, Object> m;
        try {
            m = Json.object(text);
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException(file + " is not valid JSON: fix it or run gluonify login <zone> again", e);
        }
        final Map<String, Zone> zones = new LinkedHashMap<>();
        for (final Map.Entry<String, Object> e : Json.obj(m, "zones").entrySet()) {
            if (!validZone(e.getKey()) || !(e.getValue() instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            final Map<String, Object> z = (Map<String, Object>) e.getValue();
            final String d = Json.str(z, "discovery");
            final String ca = Json.str(z, "caFile");
            URI discovery;
            try {
                discovery = d == null ? Discovery.defaultUrl(e.getKey()) : URI.create(d);
            }
            catch (final IllegalArgumentException ex) {
                discovery = Discovery.defaultUrl(e.getKey());
            }
            zones.put(e.getKey(), new Zone(e.getKey(), discovery, Json.str(z, "environment"), ca == null ? null : Path.of(ca)));
        }
        final String cur = Json.str(m, "current");
        return new DeveloperConfig(cur != null && zones.containsKey(cur) ? cur : null, zones);
    }

    /** Writes the file (0600, folder 0700). */
    public void write(final Path home) {
        final Map<String, Object> zs = new LinkedHashMap<>();
        for (final Zone z : zones.values()) {
            final Map<String, Object> m = new LinkedHashMap<>();
            m.put("discovery", z.discovery().toString());
            if (z.environment() != null) {
                m.put("environment", z.environment());
            }
            if (z.caFile() != null) {
                m.put("caFile", z.caFile().toAbsolutePath().toString());
            }
            zs.put(z.name(), m);
        }
        final Map<String, Object> root = new LinkedHashMap<>();
        if (current != null) {
            root.put("current", current);
        }
        root.put("zones", zs);
        try {
            SecureFiles.write(home.resolve(FILE), Json.write(root) + "\n");
        }
        catch (final IOException e) {
            throw new UncheckedIOException("cannot write " + home.resolve(FILE), e);
        }
    }

    /** The current zone (that of the last login). */
    public Optional<Zone> current() {
        return current == null ? Optional.empty() : zone(current);
    }

    public Optional<Zone> zone(final String name) {
        return Optional.ofNullable(zones.get(name));
    }

    public Map<String, Zone> zones() {
        return zones;
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    /** A copy with this zone added or replaced, which becomes the current zone. */
    public DeveloperConfig with(final Zone zone) {
        final Map<String, Zone> z = new LinkedHashMap<>(zones);
        z.put(zone.name(), zone);
        return new DeveloperConfig(zone.name(), z);
    }

    /** A copy without this zone; if it was the current zone, the current one becomes the first remaining. */
    public DeveloperConfig without(final String zone) {
        final Map<String, Zone> z = new LinkedHashMap<>(zones);
        z.remove(zone);
        final String cur = zone.equals(current) ? z.keySet().stream().findFirst().orElse(null) : current;
        return new DeveloperConfig(cur, z);
    }
}
