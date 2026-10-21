package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * A contract rule that is not respected ({@code contracts.md} § 3.2, § 3.4): structured form of {@code details}, translated by the consoles from {@code code}.
 * {@code message} is the English sentence of {@code violations} (compatibility with current callers), never shown as is by a console.
 *
 * @param code      stable code ({@link #CODES})
 * @param field     field of the specification concerned ({@code memoryMb}, {@code uses[2]}...), or null
 * @param limit     contract cap
 * @param used      current usage (organization rules)
 * @param requested requested value
 * @param value     refused value (name of a service, storage kind...)
 * @param source    {@code organization} (Higgs's contract) or {@code top} (limit of the {@code <uuid>.gluonify} space, stricter)
 * @param message   English sentence
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
    /** Refusal when the Raft command is applied: a simultaneous deployment took the room ({@code contracts.md} § 3.5). */
    public static final String CONCURRENT_QUOTA = "concurrent-quota";
    public static final List<String> CODES = List.of(ORGANIZATION_UNKNOWN, ORGANIZATION_SUSPENDED, ORGANIZATION_REQUIRED, ENVIRONMENT_NOT_ALLOWED, SERVICE_NOT_ALLOWED,
            GDOWN_DATABASE_NOT_ALLOWED, DISTRIBUTED_NOT_ALLOWED, LOCKS_NOT_ALLOWED, SLEEP_NOT_ALLOWED, PORTS_NOT_ALLOWED, NAS_NOT_ALLOWED, ROUTE_KIND_NOT_ALLOWED,
            ROUTE_APP_FOREIGN, REPLICAS, MEMORY_PER_REPLICA, SCRATCH_PER_REPLICA, DISTRIBUTED_QUOTA, APPS, RESERVED_MEMORY, STORAGE, DOMAINS, CONCURRENT_QUOTA);
    /** Organization rules, re-checked when the Raft command is applied. */
    public static final List<String> ORGANIZATION_RULES = List.of(APPS, RESERVED_MEMORY, STORAGE, DOMAINS);

    public static final String SOURCE_ORGANIZATION = "organization";
    public static final String SOURCE_TOP = "top";

    /** A cap is exceeded. */
    public static Violation over(final String code, final String field, final long limit, final Long used, final long requested, final String source, final String message) {
        return new Violation(code, field, limit, used, requested, null, source, message);
    }

    /** Value not allowed. */
    public static Violation refused(final String code, final String field, final String value, final String message) {
        return new Violation(code, field, null, null, null, value, SOURCE_ORGANIZATION, message);
    }

    /** Organization rule (sum over applications), re-checked when the command is applied. */
    @JsonIgnore
    public boolean organizationRule() { return ORGANIZATION_RULES.contains(code); }
}
