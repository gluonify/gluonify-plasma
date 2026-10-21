package io.gluonify.plasma.services;

import io.gluonify.plasma.services.internal.Http;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * How to reach a Gluonify service, with no configuration at all: the address and the token source are derived from where the
 * application runs. Resolution order, the same for every service:
 * <ol>
 *   <li><b>Explicit override</b> ({@link Endpoint.Source#OVERRIDE}): {@code GLUONIFY_SERVICE_<APP>_URL} as a system property, or as an
 *       environment variable outside the platform; token {@code GLUONIFY_<APP>_TOKEN} (fixed) or {@code GLUONIFY_<APP>_TOKEN_FILE} (reread on every call),
 *       system property first, otherwise no token.</li>
 *   <li><b>On the platform</b> ({@link Endpoint.Source#PLATFORM}, {@code GLUONIFY_ENVIRONMENT} present): the address injected for a service in
 *       {@code uses} and the application token file ({@code GLUONIFY_<APP>_TOKEN_FILE}, reread on every call). Service absent: the message says
 *       that the platform did not grant it (application not built by Bup with the SDK module, which Bup detects to derive
 *       {@code uses}, or a call outside the SDK that requires an explicit {@code uses} in {@code gluonify.json}).</li>
 *   <li><b>On the developer workstation</b> ({@link Endpoint.Source#DEVELOPER}): the zone of {@code ~/.gluonify/config.json} (or
 *       {@code GLUONIFY_ZONE}), written by {@code gluonify login <zone>}; the service's public address comes from the discovery document
 *       {@code https://<zone>/.well-known/gluonify} (kept 5 minutes); the token is the developer's ({@code ~/.gluonify/tokens/<zone>.json},
 *       renewed with the refresh token).</li>
 *   <li><b>Otherwise</b>: a {@link GluonifyServicesException} that says what to do ({@code gluonify login}, or {@code GluonifyTestServices} in tests).</li>
 * </ol>
 *
 * <pre>{@code
 * Endpoint crystallon = GluonifyServices.endpoint("crystallon");
 * HttpRequest.newBuilder(crystallon.resolve("/v1/render")).header("Authorization", "Bearer " + crystallon.token().orElseThrow());
 * }</pre>
 *
 * <p>Java 17, no dependency. Thread-safe. No {@link HttpClient} in a static field (native image): the instance's one is created on the
 * first network call, only on the developer workstation. Nothing is logged, no token appears in a message.
 */
public final class GluonifyServices {
    /** Variable present on the platform only: the application's environment ({@code SBX}, {@code DEV}, {@code QUA}, {@code PRD}). */
    public static final String ENV_ENVIRONMENT = "GLUONIFY_ENVIRONMENT";
    /** Variable or property: the zone to use on the developer workstation, instead of the current zone of {@code config.json}. */
    public static final String ENV_ZONE = "GLUONIFY_ZONE";
    /** Lifetime of the discovery document in memory. */
    public static final Duration DISCOVERY_TTL = Duration.ofMinutes(5);

    private static final Pattern SERVICE = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Object DEFAULTS_LOCK = new Object();
    private static final AtomicReference<GluonifyServices> DEFAULT_INSTANCE = new AtomicReference<>();

    private final Function<String, String> environment;
    private final Function<String, String> properties;
    private final Path home;
    private final Clock clock;
    private final Duration discoveryTtl;
    private final HttpClient configuredHttp;
    private final Map<String, HttpClient> clients = new ConcurrentHashMap<>();
    private final Map<URI, Cached> discoveries = new ConcurrentHashMap<>();

    private record Cached(Discovery discovery, Instant at) {
    }

    private GluonifyServices(final Builder b) {
        this.environment = b.environment;
        this.properties = b.properties;
        this.home = b.home != null ? b.home : DeveloperConfig.home(b.environment, b.properties);
        this.clock = b.clock;
        this.discoveryTtl = b.discoveryTtl;
        this.configuredHttp = b.httpClient;
    }

    /** The process instance: real environment and system properties, folder {@code ~/.gluonify} (or {@code GLUONIFY_HOME}). Created on the first call. */
    public static GluonifyServices defaults() {
        GluonifyServices d = DEFAULT_INSTANCE.get();
        if (d == null) {
            synchronized (DEFAULTS_LOCK) {
                d = DEFAULT_INSTANCE.get();
                if (d == null) {
                    d = builder().build();
                    DEFAULT_INSTANCE.set(d);
                }
            }
        }
        return d;
    }

    /** Shortcut for {@code defaults().resolve(service)}. @throws GluonifyServicesException if the service cannot be reached */
    public static Endpoint endpoint(final String service) {
        return defaults().resolve(service);
    }

    /** Shortcut for {@code defaults().resolveOptional(service)}. */
    public static Optional<Endpoint> optional(final String service) {
        return defaults().resolveOptional(service);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The Gluonify folder of this instance ({@code ~/.gluonify}). */
    public Path home() {
        return home;
    }

    /**
     * Resolves the service.
     *
     * @param service name of the service as written in {@code uses} ({@code crystallon})
     * @throws GluonifyServicesException if the service cannot be reached; the message says what to do
     * @throws IllegalArgumentException  if the name is not a service name
     */
    public Endpoint resolve(final String service) {
        final String s = name(service);
        final Endpoint override = override(s);
        if (override != null) {
            return override;
        }
        if (onPlatform()) {
            return platform(s);
        }
        return developer(s);
    }

    /**
     * Like {@link #resolve(String)}, but empty when the service is simply not available: absent from {@code uses} on the platform, no known
     * cluster on the workstation, or a zone that does not publish this service. For an optional service. Other failures (expired session, unreachable
     * discovery) always throw a {@link GluonifyServicesException}.
     */
    public Optional<Endpoint> resolveOptional(final String service) {
        final String s = name(service);
        final Endpoint override = override(s);
        if (override != null) {
            return Optional.of(override);
        }
        if (onPlatform()) {
            return blank(environment.apply(urlKey(s))) ? Optional.empty() : Optional.of(platform(s));
        }
        final DeveloperConfig config = DeveloperConfig.read(home);
        final Optional<DeveloperConfig.Zone> zone = zone(config);
        if (zone.isEmpty()) {
            return Optional.empty();
        }
        final Discovery d = discovery(zone.get());
        if (d.service(s).isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(developer(s, zone.get(), d));
    }

    /** True on the platform ({@code GLUONIFY_ENVIRONMENT} present). */
    public boolean onPlatform() {
        return !blank(environment.apply(ENV_ENVIRONMENT));
    }

    /** Forgets the discovery documents kept in memory. */
    public void invalidate() {
        discoveries.clear();
    }

    /** {@code crystallon} → {@code GLUONIFY_SERVICE_CRYSTALLON_URL} ; {@code my-svc} → {@code GLUONIFY_SERVICE_MY_SVC_URL}. */
    public static String urlKey(final String service) {
        return "GLUONIFY_SERVICE_" + upper(service) + "_URL";
    }

    /** {@code crystallon} → {@code GLUONIFY_CRYSTALLON_TOKEN}. */
    public static String tokenKey(final String service) {
        return "GLUONIFY_" + upper(service) + "_TOKEN";
    }

    /** {@code crystallon} → {@code GLUONIFY_CRYSTALLON_TOKEN_FILE} (same rule as {@code ServiceToken.envName} of the SDK). */
    public static String tokenFileKey(final String service) {
        return tokenKey(service) + "_FILE";
    }

    // ------------------------------------------------------------------------------------------------ 1. override

    private Endpoint override(final String s) {
        final String key = urlKey(s);
        String url = properties.apply(key);
        if (blank(url) && !onPlatform()) {
            url = environment.apply(key);
        }
        if (blank(url)) {
            return null;
        }
        final URI uri = uri(url, key);
        return new Endpoint(s, uri, overrideTokens(s), Endpoint.Source.OVERRIDE, null, null);
    }

    private Supplier<String> overrideTokens(final String s) {
        final String token = first(properties.apply(tokenKey(s)), null);
        if (token != null) {
            return fixed(token);
        }
        final String file = properties.apply(tokenFileKey(s));
        if (!blank(file)) {
            return file(Path.of(file.strip()), s);
        }
        final String envToken = environment.apply(tokenKey(s));
        if (!blank(envToken)) {
            return fixed(envToken);
        }
        final String envFile = environment.apply(tokenFileKey(s));
        if (!blank(envFile)) {
            return file(Path.of(envFile.strip()), s);
        }
        return () -> null;
    }

    // ------------------------------------------------------------------------------------------------ 2. platform

    private Endpoint platform(final String s) {
        final String key = urlKey(s);
        final String url = environment.apply(key);
        if (blank(url)) {
            throw new GluonifyServicesException("the platform did not grant the service \"" + s + "\" to this application (no " + key + "): build it with Bup "
                    + "with the Gluonify SDK module of the service on its runtime classpath, or, for a call outside the SDK, add \"" + s + "\" to \"uses\" in gluonify.json; then deploy again");
        }
        final String file = environment.apply(tokenFileKey(s));
        final Supplier<String> tokens;
        if (!blank(file)) {
            tokens = file(Path.of(file.strip()), s);
        }
        else if (!blank(environment.apply(tokenKey(s)))) {
            tokens = fixed(environment.apply(tokenKey(s)));
        }
        else {
            tokens = () -> null; // platform without service tokens: the service will answer 401 if it requires one
        }
        return new Endpoint(s, uri(url, key), tokens, Endpoint.Source.PLATFORM, null, null);
    }

    // ------------------------------------------------------------------------------------------------ 3. developer workstation

    private Endpoint developer(final String s) {
        final DeveloperConfig config = DeveloperConfig.read(home);
        final DeveloperConfig.Zone zone = zone(config).orElseThrow(() -> nothingKnown(s, config));
        final Discovery d = discovery(zone);
        return developer(s, zone, d);
    }

    private Endpoint developer(final String s, final DeveloperConfig.Zone zone, final Discovery d) {
        final Discovery.Service svc = d.service(s).orElseThrow(() -> new GluonifyServicesException("the zone " + zone.name() + " does not publish the service "
                + s + (d.services().isEmpty() ? " (it publishes no service)" : " (it publishes: " + String.join(", ", d.services().keySet()) + ")")
                + ": is the service deployed in this cluster? Or set " + urlKey(s)));
        final DeveloperTokens.Session session = DeveloperTokens.read(home, zone.name()).orElseThrow(() -> DeveloperTokens.notLoggedIn(zone.name()));
        if (svc.audience() != null && !session.audiences().isEmpty() && !session.audiences().contains(svc.audience())) {
            throw new GluonifyServicesException("the developer token of zone " + zone.name() + " is not issued for " + svc.audience() + " (service " + s
                    + "): run gluonify login " + zone.name() + " again (the Charm client " + d.clientId() + " must list this audience)");
        }
        final Path h = home;
        final String z = zone.name();
        final Path ca = zone.caFile();
        final Supplier<String> tokens = () -> DeveloperTokens.accessToken(h, z, () -> http(ca), clock);
        return new Endpoint(s, svc.url(), tokens, Endpoint.Source.DEVELOPER, svc.audience(), z);
    }

    private Optional<DeveloperConfig.Zone> zone(final DeveloperConfig config) {
        final String wanted = first(properties.apply(ENV_ZONE), environment.apply(ENV_ZONE));
        if (wanted != null) {
            final String w = wanted.toLowerCase(Locale.ROOT);
            return Optional.of(config.zone(w).orElseThrow(() -> new GluonifyServicesException(ENV_ZONE + "=" + w + " but this zone is not known: run gluonify login " + w)));
        }
        return config.current();
    }

    /** The zone's discovery document (memory of {@link #DISCOVERY_TTL}; if it is unreachable, the last copy read is kept). */
    public Discovery discovery(final DeveloperConfig.Zone zone) {
        final URI url = zone.discovery();
        final Cached cached = discoveries.get(url);
        final Instant now = clock.instant();
        if (cached != null && now.isBefore(cached.at().plus(discoveryTtl))) {
            return cached.discovery();
        }
        try {
            final Discovery d = fetchDiscovery(url, zone);
            discoveries.put(url, new Cached(d, now));
            return d;
        }
        catch (final GluonifyServicesException e) {
            if (cached != null) {
                return cached.discovery(); // cluster momentarily unreachable: the last copy is better than a failure
            }
            throw e;
        }
    }

    private Discovery fetchDiscovery(final URI url, final DeveloperConfig.Zone zone) {
        if (!Http.secure(url)) {
            throw new GluonifyServicesException("the discovery address of zone " + zone.name() + " must be https: " + url);
        }
        final Http.Response r;
        try {
            r = Http.getJson(http(zone.caFile()), url);
        }
        catch (final IOException e) {
            throw new GluonifyServicesException("cannot read the discovery document of zone " + zone.name() + " at " + url + " (" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()) + "): is the cluster running?", e);
        }
        catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GluonifyServicesException("the discovery of zone " + zone.name() + " was interrupted", e);
        }
        if (!r.ok()) {
            throw new GluonifyServicesException("the discovery document of zone " + zone.name() + " at " + url + " is not available (HTTP " + r.status()
                    + "): is the cluster running? Discovery is disabled in PRD (gluonify.higgs.discovery.public)");
        }
        return Discovery.of(r.json());
    }

    private GluonifyServicesException nothingKnown(final String s, final DeveloperConfig config) {
        return new GluonifyServicesException("cannot reach the service " + s + ": this is not the Gluonify platform (" + ENV_ENVIRONMENT + " is not set) and no cluster "
                + "is known (" + (config.isEmpty() ? "no " + home.resolve(DeveloperConfig.FILE) : "no current zone in " + home.resolve(DeveloperConfig.FILE))
                + "). Run gluonify login <zone> (for example gluonify login gluonify.lan), set " + urlKey(s) + ", or use fake services in tests "
                + "(GluonifyTestServices.start())");
    }

    HttpClient http(final Path caFile) {
        if (configuredHttp != null) {
            return configuredHttp;
        }
        return clients.computeIfAbsent(caFile == null ? "" : caFile.toAbsolutePath().toString(), k -> {
            try {
                return Http.client(caFile);
            }
            catch (final IllegalArgumentException e) {
                throw new GluonifyServicesException(e.getMessage() + ": fix caFile in " + home.resolve(DeveloperConfig.FILE), e);
            }
        });
    }

    // ------------------------------------------------------------------------------------------------ tools

    private static Supplier<String> fixed(final String token) {
        final String t = token.strip();
        return () -> t;
    }

    /** The token is reread on every call; file absent or empty: no token (not yet dropped by the agent). */
    private static Supplier<String> file(final Path path, final String service) {
        return () -> {
            try {
                final String v = Files.readString(path, StandardCharsets.UTF_8).strip();
                return v.isEmpty() ? null : v;
            }
            catch (final NoSuchFileException e) {
                return null;
            }
            catch (final IOException | RuntimeException e) {
                throw new GluonifyServicesException("cannot read the token file of " + service + " " + path + " (" + e.getClass().getSimpleName() + ")", e);
            }
        };
    }

    private static URI uri(final String url, final String key) {
        final URI u;
        try {
            u = URI.create(url.strip());
        }
        catch (final IllegalArgumentException e) {
            throw new GluonifyServicesException(key + " is not a valid URL", e);
        }
        final String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("ws") && !scheme.equals("wss") || u.getHost() == null) {
            throw new GluonifyServicesException(key + " must be an absolute http(s) URL");
        }
        return URI.create(u.toString().replaceAll("/+$", ""));
    }

    private static String name(final String service) {
        Objects.requireNonNull(service, "service");
        final String s = service.strip().toLowerCase(Locale.ROOT);
        if (!SERVICE.matcher(s).matches()) {
            throw new IllegalArgumentException("not a service name: " + service);
        }
        return s;
    }

    private static String upper(final String service) {
        return service.toUpperCase(Locale.ROOT).replace('-', '_');
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    private static String first(final String a, final String b) {
        if (!blank(a)) return a.strip();
        return !blank(b) ? b.strip() : null;
    }

    /** A separate instance: environment, properties, folder, clock and HTTP client supplied (tests, tools). */
    public static final class Builder {
        private Function<String, String> environment = System::getenv;
        private Function<String, String> properties = System::getProperty;
        private Path home;
        private Clock clock = Clock.systemUTC();
        private Duration discoveryTtl = DISCOVERY_TTL;
        private HttpClient httpClient;

        private Builder() {
        }

        /** @param env the environment variables (default: {@link System#getenv(String)}) */
        public Builder environment(final Function<String, String> env) {
            this.environment = Objects.requireNonNull(env, "env");
            return this;
        }

        /** @param props the system properties (default: {@link System#getProperty(String)}) */
        public Builder properties(final Function<String, String> props) {
            this.properties = Objects.requireNonNull(props, "props");
            return this;
        }

        /** @param dir the Gluonify folder (default: {@code GLUONIFY_HOME}, otherwise {@code ~/.gluonify}) */
        public Builder home(final Path dir) {
            this.home = Objects.requireNonNull(dir, "dir");
            return this;
        }

        public Builder clock(final Clock c) {
            this.clock = Objects.requireNonNull(c, "clock");
            return this;
        }

        /** @param ttl lifetime of the discovery document in memory (default: 5 minutes) */
        public Builder discoveryTtl(final Duration ttl) {
            this.discoveryTtl = Objects.requireNonNull(ttl, "ttl");
            return this;
        }

        /** @param client the HTTP client for discovery and renewal (default: a client created on the first call, per certificate authority) */
        public Builder httpClient(final HttpClient client) {
            this.httpClient = Objects.requireNonNull(client, "client");
            return this;
        }

        public GluonifyServices build() {
            return new GluonifyServices(this);
        }
    }
}
