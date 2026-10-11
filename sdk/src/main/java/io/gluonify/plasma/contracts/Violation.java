package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * Une règle de contrat non respectée ({@code contracts.md} § 3.2, § 3.4) : forme structurée de {@code details}, traduite par les consoles à partir de {@code code}.
 * {@code message} est la phrase en français de {@code violations} (compatibilité avec les appelants actuels), jamais montrée telle quelle par une console.
 *
 * @param code      code stable ({@link #CODES})
 * @param field     champ de la spécification en cause ({@code memoryMb}, {@code uses[2]}…), ou null
 * @param limit     plafond du contrat
 * @param used      usage en place (règles d'organisation)
 * @param requested valeur demandée
 * @param value     valeur refusée (nom d'un service, genre de stockage…)
 * @param source    {@code organization} (le contrat de Higgs) ou {@code top} (limite de l'espace {@code <uuid>.gluonify}, plus stricte)
 * @param message   phrase en français
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Violation(String code, String field, Long limit, Long used, Long requested, String value, String source, String message) {
    public static final String ORGANIZATION_UNKNOWN = "organization-unknown";
    public static final String ORGANIZATION_SUSPENDED = "organization-suspended";
    public static final String ORGANIZATION_REQUIRED = "organization-required";
    public static final String ENVIRONMENT_NOT_ALLOWED = "environment-not-allowed";
    public static final String SERVICE_NOT_ALLOWED = "service-not-allowed";
    public static final String GDOWN_DATABASE_NOT_ALLOWED = "gdown-database-not-allowed";
    public static final String DISTRIBUTED_NOT_ALLOWED = "distributed-not-allowed";
    public static final String LOCKS_NOT_ALLOWED = "locks-not-allowed";
    public static final String SLEEP_NOT_ALLOWED = "sleep-not-allowed";
    public static final String PORTS_NOT_ALLOWED = "ports-not-allowed";
    public static final String NAS_NOT_ALLOWED = "nas-not-allowed";
    public static final String ROUTE_KIND_NOT_ALLOWED = "route-kind-not-allowed";
    public static final String ROUTE_APP_FOREIGN = "route-app-foreign";
    public static final String REPLICAS = "replicas";
    public static final String MEMORY_PER_REPLICA = "memory-per-replica";
    public static final String SCRATCH_PER_REPLICA = "scratch-per-replica";
    public static final String DISTRIBUTED_QUOTA = "distributed-quota";
    public static final String APPS = "apps";
    public static final String RESERVED_MEMORY = "reserved-memory";
    public static final String STORAGE = "storage";
    public static final String DOMAINS = "domains";
    /** Refus à l'application de la commande Raft : un déploiement simultané a pris la place ({@code contracts.md} § 3.5). */
    public static final String CONCURRENT_QUOTA = "concurrent-quota";
    public static final List<String> CODES = List.of(ORGANIZATION_UNKNOWN, ORGANIZATION_SUSPENDED, ORGANIZATION_REQUIRED, ENVIRONMENT_NOT_ALLOWED, SERVICE_NOT_ALLOWED,
            GDOWN_DATABASE_NOT_ALLOWED, DISTRIBUTED_NOT_ALLOWED, LOCKS_NOT_ALLOWED, SLEEP_NOT_ALLOWED, PORTS_NOT_ALLOWED, NAS_NOT_ALLOWED, ROUTE_KIND_NOT_ALLOWED,
            ROUTE_APP_FOREIGN, REPLICAS, MEMORY_PER_REPLICA, SCRATCH_PER_REPLICA, DISTRIBUTED_QUOTA, APPS, RESERVED_MEMORY, STORAGE, DOMAINS, CONCURRENT_QUOTA);
    /** Règles d'organisation, refaites à l'application de la commande Raft. */
    public static final List<String> ORGANIZATION_RULES = List.of(APPS, RESERVED_MEMORY, STORAGE, DOMAINS);

    public static final String SOURCE_ORGANIZATION = "organization";
    public static final String SOURCE_TOP = "top";

    /** Dépassement d'un plafond. */
    public static Violation over(String code, String field, long limit, Long used, long requested, String source, String message) {
        return new Violation(code, field, limit, used, requested, null, source, message);
    }

    /** Valeur non permise. */
    public static Violation refused(String code, String field, String value, String message) {
        return new Violation(code, field, null, null, null, value, SOURCE_ORGANIZATION, message);
    }

    /** Règle d'organisation (somme sur les applications), refaite à l'application de la commande. */
    @JsonIgnore
    public boolean organizationRule() { return ORGANIZATION_RULES.contains(code); }
}
