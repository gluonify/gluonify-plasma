package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.AppShare;
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
import java.util.logging.Level;
import java.util.logging.Logger;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * {@code GET /q/usage} : le contrat d'usage {@code gluonify-usage/1} du composant ({@code usage-mcp.md} § 3), prêt à l'emploi.
 * <p>Jamais anonyme : 401 sans identité, 403 sans le rôle {@code gluonify:usage:read} (commun) ni {@code gluonify:<produit>:usage:read}. Pas de secret,
 * pas de donnée de client autre que l'identité des applications attribuées ; {@code Cache-Control: no-store}. Le point d'accès ne doit pas coûter plus que
 * {@code /q/health} : quelques fichiers {@code /proc}, et les valeurs que fournit l'éventuel {@link UsageContributor}.
 * <p>Opt-in : le SDK n'est pas indexé par défaut ; une application l'active avec {@code quarkus.index-dependency.plasma-sdk.group-id=io.gluonify.plasma} et
 * {@code quarkus.index-dependency.plasma-sdk.artifact-id=gluonify-plasma-sdk}, et écrit un bean {@link UsageContributor} si elle sait attribuer.
 * Propriétés : {@code quarkus.application.name} (le composant, sans le préfixe {@code gluonify-}) et {@code quarkus.application.version}, surchargeables
 * par {@code gluonify.usage.component} ; {@code gluonify.usage.instance} et {@code gluonify.usage.node} (facultatifs).
 */
@ApplicationScoped
@Path(UsageResource.PATH)
public class UsageResource {
    public static final String PATH = "/q/usage";
    /** Rôle commun à tous les composants. */
    public static final String ROLE = "gluonify:usage:read";

    private static final Logger LOG = Logger.getLogger(UsageResource.class.getName());

    private final ProcessUsage usage;
    private final LongSupplier clock;
    private final String component, version, instance, node;
    private final java.util.function.Supplier<List<UsageContributor>> contributors;

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
        }, component.orElse(name.startsWith("gluonify-") ? name.substring("gluonify-".length()) : name), version, instance.orElse(null), node.orElse(null));
    }

    UsageResource(final ProcessUsage usage, final LongSupplier clock, final java.util.function.Supplier<List<UsageContributor>> contributors,
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
            return Response.status(Response.Status.UNAUTHORIZED).header("Cache-Control", "no-store").build();
        }
        if (!security.isUserInRole(ROLE) && !security.isUserInRole("gluonify:" + component + ":usage:read")) {
            return Response.status(Response.Status.FORBIDDEN).header("Cache-Control", "no-store").build();
        }
        return Response.ok(report()).header("Cache-Control", "no-store").build();
    }

    UsageReport report() {
        final List<AppShare> shares = new ArrayList<>();
        String attribution = UsageReport.NONE;
        try {
            for (final UsageContributor c : contributors.get()) {
                final var rows = c.byApp();
                if (rows != null && !rows.isEmpty()) {
                    shares.addAll(rows);
                    attribution = weakest(attribution, c.attribution());
                }
            }
        } catch (RuntimeException e) {
            // Un contributeur en panne ne fait pas échouer le contrat : processus seul, sans détail dans la réponse.
            LOG.log(Level.WARNING, "contributeur d'usage en échec : {0}", e.getClass().getSimpleName());
            shares.clear();
            attribution = UsageReport.NONE;
        }
        return UsageReport.of(component, version, instance, node, clock.getAsLong(), usage.process(), usage.cgroup(), attribution, shares);
    }

    /** La confiance la plus faible gagne : {@code measured} > {@code prorata} > {@code estimated} ; {@code none} cède devant toute attribution. */
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
