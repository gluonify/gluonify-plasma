package io.gluonify.plasma.quarkus.mcp;

import io.gluonify.plasma.mcp.McpContract;
import io.quarkus.arc.ClientProxy;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /mcp}: the application's MCP tools ({@link McpTool} methods of its {@link McpToolbox} beans), for Prism only
 * ({@code gluonify-codex/app-mcp.md}). Served as soon as {@code gluonify-plasma-mcp} is on the classpath (its jar is a bean archive and carries the
 * marker {@link McpContract#MARKER} that Bup reads to set {@code mcp: true}).
 * <p><b>Caller identity</b> (§ 8 d3: the existing application-to-service token, no new header): the {@code Authorization: Bearer} Charm token is
 * verified by the application's own {@code quarkus-oidc} (or {@code quarkus-smallrye-jwt}) configuration: signature against Charm's JWKS, issuer,
 * expiry; this resource then requires a JWT principal whose {@code aud} contains the application's audience ({@code gluonify.mcp.audience}, default
 * {@code quarkus.oidc.token.audience}, else {@code gluonify-<quarkus.application.name>}). Otherwise 401 with {@code WWW-Authenticate: Bearer}. The
 * token must then be the relay's service token ({@link #isRelay}), otherwise 403. The relay may call {@code initialize}, {@code tools/list} and the read tools; a {@code write} tool needs {@link McpContract#WRITE_ROLE} in the
 * {@code roles} claim (403 otherwise, before the tool runs).
 * <p>Responses are not marked {@code untrusted} here: Prism marks every relayed response itself (§ 2, § 8 d6), since a marker set by the
 * application could not be trusted anyway.
 */
@ApplicationScoped
@Path(McpContract.PATH)
@PermitAll // the resource decides 401/403 itself (as UsageResource), also under quarkus.security.jaxrs.deny-unannotated-endpoints=true
public class McpResource {
    private static final Logger LOG = Logger.getLogger("gluonify.mcp");
    private static final String BRAND_PREFIX = "gluonify-";

    @Inject
    Instance<McpToolbox> toolboxes;
    @Inject
    Instance<ObjectMapper> mappers;
    @Inject
    SecurityIdentity identity;

    @ConfigProperty(name = "gluonify.mcp.audience")
    Optional<String> audience;
    @ConfigProperty(name = "gluonify.mcp.relay-app", defaultValue = "prism")
    String relayApp;
    @ConfigProperty(name = "quarkus.oidc.token.audience")
    Optional<List<String>> oidcAudience;
    @ConfigProperty(name = "quarkus.application.name", defaultValue = "unknown")
    String applicationName;
    @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown")
    String applicationVersion;

    private final AtomicReference<McpServer> server = new AtomicReference<>();

    /** Reads and checks the tools at startup: an invalid declaration stops the application. */
    void onStart(@Observes final StartupEvent ignored) {
        server();
    }

    McpServer server() {
        McpServer s = server.get();
        if (s == null) {
            synchronized (this) {
                s = server.get();
                if (s == null) {
                    final List<Object> beans = new ArrayList<>();
                    toolboxes.forEach(beans::add);
                    final ObjectMapper mapper = mappers.isResolvable() ? mappers.get() : new ObjectMapper();
                    final List<Object> targets = new ArrayList<>();
                    for (final Object b : beans) {
                        targets.add(b instanceof ClientProxy ? ClientProxy.unwrap(b) : b);
                    }
                    s = new McpServer(McpRegistry.of(targets, mapper, LOG::warning), mapper, applicationVersion);
                    final int count = s.registry().size();
                    LOG.info(() -> "MCP: " + count + " tool(s) at " + McpContract.PATH);
                    server.set(s);
                }
            }
        }
        return s;
    }

    /** The audiences this application accepts on {@code /mcp}. */
    Set<String> audiences() {
        if (audience.isPresent() && !audience.get().isBlank()) {
            return Set.of(audience.get().strip());
        }
        if (oidcAudience.isPresent() && !oidcAudience.get().isEmpty()) {
            return Set.copyOf(oidcAudience.get());
        }
        return Set.of(applicationName.startsWith(BRAND_PREFIX) ? applicationName : BRAND_PREFIX + applicationName);
    }

    @POST
    @Consumes(MediaType.WILDCARD)
    public Response post(final InputStream body, @HeaderParam("Content-Length") final String length) throws IOException {
        final JsonWebToken jwt = token();
        if (jwt == null) {
            return Response.status(401).header("WWW-Authenticate", "Bearer").type(MediaType.APPLICATION_JSON)
                    .entity("{\"error\":\"a valid application token is required\"}").build();
        }
        if (!isRelay(jwt)) {
            return Response.status(403).type(MediaType.APPLICATION_JSON).entity("{\"error\":\"only the platform's relay (Prism) may call /mcp\"}").build();
        }
        final McpCaller caller = caller(jwt);
        if (length != null && length.chars().allMatch(Character::isDigit) && !length.isEmpty() && Long.parseLong(length) > McpContract.MAX_BODY_BYTES) {
            return reply(McpServer.tooLarge());
        }
        final byte[] bytes = body == null ? new byte[0] : body.readNBytes(McpContract.MAX_BODY_BYTES + 1);
        return reply(server().handle(bytes, caller));
    }

    private static Response reply(final McpServer.Reply r) {
        final Response.ResponseBuilder b = Response.status(r.status());
        return r.body() == null ? b.build() : b.type(MediaType.APPLICATION_JSON).entity(r.body()).build();
    }

    /** The verified token of the application's audience, or null (anonymous, not a JWT, another audience): 401. */
    JsonWebToken token() {
        if (identity == null || identity.isAnonymous() || !(identity.getPrincipal() instanceof JsonWebToken jwt)) {
            return null;
        }
        final Set<String> aud = strings(jwt.getClaim("aud"));
        return aud.stream().anyMatch(audiences()::contains) ? jwt : null;
    }

    /**
     * True for an application-to-service token of the platform's relay: {@code gluonify_kind} = {@code service} and {@code gluonify_app} =
     * {@code gluonify.mcp.relay-app} ({@code prism}), the claims Higgs has Charm put in every service token ({@code CharmClient.serviceRequest}). A
     * customer's user or API token of the same audience, or another application's service token, is refused (403): app-mcp.md § 3.
     */
    boolean isRelay(final JsonWebToken jwt) {
        return strings(jwt.getClaim("gluonify_kind")).equals(Set.of("service")) && strings(jwt.getClaim("gluonify_app")).equals(Set.of(relayApp));
    }

    /** The caller given to the tools. */
    McpCaller caller(final JsonWebToken jwt) {
        final Set<String> roles = new HashSet<>(identity.getRoles());
        roles.addAll(strings(jwt.getClaim("roles")));
        return new McpCaller(jwt.getSubject(), roles, Optional.of(jwt));
    }

    /** A string claim or a list of strings ({@code aud} is either), as a set. */
    private static Set<String> strings(final Object claim) {
        final Set<String> out = new HashSet<>();
        if (claim instanceof Collection<?> c) {
            for (final Object o : c) {
                out.add(unquote(String.valueOf(o)));
            }
        } else if (claim != null) {
            out.add(unquote(String.valueOf(claim)));
        }
        return out;
    }

    /** A {@code jakarta.json.JsonString} prints with its quotes. */
    private static String unquote(final String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"") ? s.substring(1, s.length() - 1) : s;
    }
}
