package io.gluonify.plasma.access;

import io.gluonify.plasma.Organizations;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The shared rule by which a platform service accepts a <b>customer token</b> in addition to the platform tokens (gluonify-codex/subscriptions.md § 3.2).
 *
 * <ul>
 *   <li>A token is a customer token when it carries the organization claim ({@value Organizations#CLAIM}, a valid organization identifier) and <b>no</b>
 *   role under the reserved prefix {@value #PLATFORM_ROLE_PREFIX}. A token with any {@code gluonify:} role is a platform token: the service treats it as
 *   before, and its {@code org:*} roles grant nothing here.</li>
 *   <li>The customer roles ({@code org:owner}, {@code org:developer}, {@code org:viewer}, {@code org:billing}) are mapped to the service's own rights by a
 *   fixed {@link Table} written in the service's code, never by configuration a customer can reach. Any other role is ignored.</li>
 *   <li>The organization is the token's, never the request's: the service bounds every read and write to {@link Customer#organization()}.</li>
 * </ul>
 *
 * Pure functions, no framework: the service calls {@link #recognise} from its own security layer (a Quarkus {@code SecurityIdentityAugmentor}, for example).
 */
public final class CustomerAccess {
    private CustomerAccess() {}

    /** Prefix of the platform roles: a token holding one is never a customer token. */
    public static final String PLATFORM_ROLE_PREFIX = "gluonify:";

    /**
     * A recognised customer token.
     *
     * @param organization  the organization of the token (a valid identifier)
     * @param customerRoles the customer roles it holds ({@code org:*}), in the order of {@link Organizations#ROLES}
     * @param rights        the service's rights these roles give, from the service's {@link Table}; empty: bounded to the organization, allowed nothing
     */
    public record Customer(String organization, Set<String> customerRoles, Set<String> rights) {
        public Customer {
            customerRoles = Set.copyOf(customerRoles);
            rights = Set.copyOf(rights);
        }
    }

    /** The fixed mapping of a service: customer role → the service's rights. Immutable once built. */
    public static final class Table {
        private final Map<String, Set<String>> rights;

        private Table(final Map<String, Set<String>> rights) {
            final Map<String, Set<String>> copy = new LinkedHashMap<>();
            rights.forEach((k, v) -> copy.put(k, Set.copyOf(v)));
            this.rights = Map.copyOf(copy);
        }

        public static Builder builder() {
            return new Builder();
        }

        /** The rights of one customer role (empty for a role the table does not grant). */
        public Set<String> rightsOf(final String customerRole) {
            return rights.getOrDefault(customerRole, Set.of());
        }

        /** The union of the rights of {@code customerRoles}. */
        public Set<String> rightsOf(final Collection<String> customerRoles) {
            final Set<String> out = new LinkedHashSet<>();
            for (String r : customerRoles) {
                out.addAll(rightsOf(r));
            }
            return out;
        }

        /** Builds a {@link Table}; refuses an unknown customer role and a right that is itself a customer role. */
        public static final class Builder {
            private final Map<String, Set<String>> rights = new LinkedHashMap<>();

            private Builder() {}

            public Builder grant(final String customerRole, final String... serviceRights) {
                if (!Organizations.isCustomerRole(customerRole)) {
                    throw new IllegalArgumentException("not a customer role: " + customerRole);
                }
                final Set<String> set = rights.computeIfAbsent(customerRole, k -> new LinkedHashSet<>());
                for (String r : serviceRights) {
                    if (r == null || r.isBlank() || Organizations.isCustomerRole(r) || r.startsWith("org:")) {
                        throw new IllegalArgumentException("a right is a role of the service, not a customer role: " + r);
                    }
                    set.add(r);
                }
                return this;
            }

            public Table build() {
                return new Table(rights);
            }
        }
    }

    /**
     * The organization named by a claim value: a string, or a collection holding exactly one string; empty when absent, several, or not a valid identifier
     * ({@link Organizations#PATTERN}).
     */
    public static Optional<String> organization(final Object claim) {
        Object v = claim;
        if (v instanceof Collection<?> c) {
            if (c.size() != 1) {
                return Optional.empty();
            }
            v = c.iterator().next();
        }
        if (!(v instanceof CharSequence s)) {
            return Optional.empty();
        }
        final String org = s.toString().strip();
        return Organizations.isValid(org) ? Optional.of(org) : Optional.empty();
    }

    /** True if the token holds a platform role (any role under {@value #PLATFORM_ROLE_PREFIX}). */
    public static boolean hasPlatformRole(final Collection<String> roles) {
        return roles != null && roles.stream().anyMatch(r -> r != null && r.startsWith(PLATFORM_ROLE_PREFIX));
    }

    /**
     * Recognises a customer token from its organization claim and its roles, and maps its customer roles through the service's {@code table}. Empty for a
     * platform token (a {@code gluonify:} role), a token without a valid organization, or no token at all.
     */
    public static Optional<Customer> recognise(final Object organizationClaim, final Collection<String> roles, final Table table) {
        if (hasPlatformRole(roles)) {
            return Optional.empty();
        }
        final Optional<String> org = organization(organizationClaim);
        if (org.isEmpty()) {
            return Optional.empty();
        }
        final Set<String> held = new LinkedHashSet<>();
        if (roles != null) {
            for (String r : Organizations.ROLES) {
                if (roles.contains(r)) {
                    held.add(r);
                }
            }
        }
        return Optional.of(new Customer(org.get(), held, table.rightsOf(held)));
    }
}
