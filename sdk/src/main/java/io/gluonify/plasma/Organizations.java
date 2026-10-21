package io.gluonify.plasma;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Organizations (the platform's customers) and customer roles.
 *
 * <p>Higgs is the source of truth for the application → organization link ({@link AppSpec#organization()}), set by a
 * platform administrator only (gluonify-codex/metering.md § 2.2). Charm attaches an account to an
 * organization and sets the {@value #CLAIM} claim in its tokens.
 *
 * <p>Customer roles, valid within their organization only (never under the reserved prefix {@code gluonify:}):
 * <ul>
 *   <li>{@value #OWNER}: everything, access and billing included;</li>
 *   <li>{@value #DEVELOPER}: repositories, builds, deployments, configuration, DNS and API;</li>
 *   <li>{@value #VIEWER}: read-only;</li>
 *   <li>{@value #BILLING}: consumption and invoices.</li>
 * </ul>
 */
public final class Organizations {
    private Organizations() {}

    /** Value displayed (filters, exports, consoles) for a resource attached to no organization; never stored. */
    public static final String UNASSIGNED = "_unassigned";
    /** Name of the Charm token claim that carries the organization (read as is by Crystallon and Interfero). */
    public static final String CLAIM = "org";
    /** Shape of an organization identifier: DNS label of 1 to 40 characters (a-z, 0-9, hyphen, neither leading nor trailing), the same as gluonify-charm. */
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,38}[a-z0-9])?");

    public static final String OWNER = "org:owner";
    public static final String DEVELOPER = "org:developer";
    public static final String VIEWER = "org:viewer";
    public static final String BILLING = "org:billing";
    /** The four customer roles, from the broadest to the narrowest. */
    public static final List<String> ROLES = List.of(OWNER, DEVELOPER, VIEWER, BILLING);
    private static final Set<String> ROLE_SET = Set.copyOf(ROLES);

    /** True if {@code org} is a valid organization identifier (null is not). */
    public static boolean isValid(final String org) { return org != null && PATTERN.matcher(org).matches(); }

    /** Rejects an invalid identifier; null (no organization) is accepted. */
    public static void requireValid(final String org) {
        if (org != null && !isValid(org)) throw new IllegalArgumentException("invalid organization (DNS label: a-z, 0-9 and -, neither leading nor trailing, 1 to 40 characters)");
    }

    /** Organization for display: {@link #UNASSIGNED} by default. */
    public static String orUnassigned(final String org) { return org == null ? UNASSIGNED : org; }

    /**
     * True if a resource of organization {@code org} (null: none) passes the filter {@code filter};
     * a null filter lets everything through, {@value #UNASSIGNED} keeps only the resources without an organization.
     */
    public static boolean matches(final String filter, final String org) { return filter == null || filter.equals(orUnassigned(org)); }

    /** True if {@code role} is one of the customer roles. */
    public static boolean isCustomerRole(final String role) { return role != null && ROLE_SET.contains(role); }
}
