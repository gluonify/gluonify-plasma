package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import jakarta.annotation.security.PermitAll;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code GET /q/usage}: the component's usage contract {@code gluonify-usage/1} ({@code usage-mcp.md} § 3), ready to use.
 * <p>Never anonymous: 401 without an identity, 403 without the role {@code gluonify:usage:read} (common) or {@code gluonify:<product>:usage:read}. No secret,
 * no customer data other than the identity of the attributed applications; {@code Cache-Control: no-store}. The endpoint must not cost more than
 * {@code /q/health}: a few {@code /proc} files, and the values supplied by the optional {@link UsageContributor}.
 * <p>Explicit activation (decision of 2026-10-17): the SDK is not indexed by default, so the endpoint appears in no application that did not
 * ask for it; a component enables it with {@code quarkus.index-dependency.plasma-sdk.group-id=io.gluonify.plasma} and
 * {@code quarkus.index-dependency.plasma-sdk.artifact-id=gluonify-plasma-sdk}, and writes a {@link UsageContributor} bean if it can attribute.
 * Properties: {@code quarkus.application.name} (the component, without the {@code gluonify-} prefix) and {@code quarkus.application.version}, overridable
 * by {@code gluonify.usage.component}; {@code gluonify.usage.instance} and {@code gluonify.usage.node} (optional).
 */
@ApplicationScoped
@Path(UsageResource.PATH)
@PermitAll // the resource decides 401/403 itself; without this a component with quarkus.security.jaxrs.deny-unannotated-endpoints=true answers 403 even with the right role
public class UsageResource {
    public static final String PATH = "/q/usage";
    /** Role common to all components. */
    public static final String ROLE = "gluonify:usage:read";

    private static final Logger LOG = Logger.getLogger(UsageResource.class.getName());
    private static final String CACHE_CONTROL = "Cache-Control";
    private static final String NO_STORE = "no-store";
    private static final String BRAND_PREFIX = "gluonify-";

    private final ProcessUsage usage;
    private final LongSupplier clock;
    private final String component;
    private final String version;
    private final String instance;
    private final String node;
    private final Supplier<List<UsageContributor>> contributors;

    @Inject
    public UsageResource(final Instance<UsageContributor> found, @ConfigProperty(name = "quarkus.application.name", defaultValue = "unknown") final String name,
            @ConfigProperty(name = "quarkus.application.version", defaultValue = "unknown") final String version,
            @ConfigProperty(name = "gluonify.usage.component") final Optional<String> component,
            @ConfigProperty(name = "gluonify.usage.instance") final Optional<String> instance,
            @ConfigProperty(name = "gluonify.usage.node") final Optional<String> node) {
        this(new ProcessUsage(), System::currentTimeMillis, () -> {
            final List<UsageContributor> all = new ArrayList<>();
            found.forEach(all::add);
            return all;
        }, component.orElse(name.startsWith(BRAND_PREFIX) ? name.substring(BRAND_PREFIX.length()) : name), version, instance.orElse(null), node.orElse(null));
    }

    /**
     * Explicit constructor (component tests: a fake {@code /proc} on a workstation without one, fixed clock and contributors; install it with
     * {@code QuarkusMock.installMockForType(..., UsageResource.class)} in a Quarkus test).
     */
    public UsageResource(final ProcessUsage usage, final LongSupplier clock, final Supplier<List<UsageContributor>> contributors,
            final String component, final String version, final String instance, final String node) {
        this.usage = usage;
        this.clock = clock;
        this.contributors = contributors;
        this.component = component;
        this.version = version;
        this.instance = instance;
        this.node = node;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response usage(@Context final SecurityContext security) {
        if (security == null || security.getUserPrincipal() == null) {
            return Response.status(Response.Status.UNAUTHORIZED).header(CACHE_CONTROL, NO_STORE).build();
        }
        if (!security.isUserInRole(ROLE) && !security.isUserInRole("gluonify:" + component + ":usage:read")) {
            return Response.status(Response.Status.FORBIDDEN).header(CACHE_CONTROL, NO_STORE).build();
        }
        return Response.ok(report()).header(CACHE_CONTROL, NO_STORE).build();
    }

    /** The component's contract as {@code GET /q/usage} returns it; public for a component that also sends it over another channel (Strange: {@code Msg.UsageReport}). */
    public UsageReport report() {
        final List<AppShare> shares = new ArrayList<>();
        String attribution = UsageReport.NONE;
        Long storage = null;
        final UsageReport.Process process = usage.process();
        try {
            for (final UsageContributor c : contributors.get()) {
                final var rows = c.byApp(process);
                if (rows != null && !rows.isEmpty()) {
                    shares.addAll(rows);
                    attribution = weakest(attribution, c.attribution());
                }
                final Long stored = c.storageBytes();
                if (stored != null) {
                    storage = (storage == null ? 0 : storage) + stored;
                }
            }
        } catch (RuntimeException e) {
            // A failing contributor does not make the contract fail: process only, with no detail in the response.
            LOG.log(Level.WARNING, "usage contributor failed: {0}", e.getClass().getSimpleName());
            shares.clear();
            attribution = UsageReport.NONE;
            storage = null;
        }
        return UsageReport.of(component, version, instance, node, clock.getAsLong(), process, usage.cgroup(), attribution, shares, storage);
    }

    /** The weakest confidence wins: {@code measured} > {@code prorata} > {@code estimated}; {@code none} yields to any attribution. */
    private static String weakest(final String current, final String next) {
        if (next == null) {
            return current.equals(UsageReport.NONE) ? UsageReport.ESTIMATED : current;
        }
        if (current.equals(UsageReport.NONE)) {
            return next;
        }
        return rank(next) > rank(current) ? next : current;
    }

    private static int rank(final String attribution) {
        return switch (attribution) {
            case UsageReport.MEASURED -> 0;
            case UsageReport.PRORATA -> 1;
            default -> 2;
        };
    }
}
