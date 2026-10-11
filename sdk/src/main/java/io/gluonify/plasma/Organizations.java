package io.gluonify.plasma;

import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Organisations (les clients de la plateforme) et rôles des clients.
 *
 * <p>Higgs est la source de vérité du lien application → organisation ({@link AppSpec#organization()}), fixé par un
 * administrateur de la plateforme seulement (gluonify-codex/metering.md § 2.2). Charm rattache un compte à une
 * organisation et pose la revendication {@value #CLAIM} dans ses jetons.
 *
 * <p>Rôles des clients, valables dans leur organisation seulement (jamais sous le préfixe réservé {@code gluonify:}) :
 * <ul>
 *   <li>{@value #OWNER} : tout, accès et facturation compris ;</li>
 *   <li>{@value #DEVELOPER} : dépôts, builds, déploiements, configuration, DNS et API ;</li>
 *   <li>{@value #VIEWER} : lecture seule ;</li>
 *   <li>{@value #BILLING} : consommation et factures.</li>
 * </ul>
 */
public final class Organizations {
    private Organizations() {}

    /** Valeur affichée (filtres, exports, consoles) pour une ressource rattachée à aucune organisation ; jamais enregistrée. */
    public static final String UNASSIGNED = "_unassigned";
    /** Nom de la revendication des jetons Charm qui porte l'organisation (lue telle quelle par Crystallon et Interfero). */
    public static final String CLAIM = "org";
    /** Forme d'un identifiant d'organisation. */
    public static final Pattern PATTERN = Pattern.compile("[a-z0-9-]{1,40}");

    public static final String OWNER = "org:owner";
    public static final String DEVELOPER = "org:developer";
    public static final String VIEWER = "org:viewer";
    public static final String BILLING = "org:billing";
    /** Les quatre rôles des clients, du plus large au plus étroit. */
    public static final List<String> ROLES = List.of(OWNER, DEVELOPER, VIEWER, BILLING);
    private static final Set<String> ROLE_SET = Set.copyOf(ROLES);

    /** Vrai si {@code org} est un identifiant d'organisation valide (null ne l'est pas). */
    public static boolean isValid(String org) { return org != null && PATTERN.matcher(org).matches(); }

    /** Refuse un identifiant invalide ; null (aucune organisation) est accepté. */
    public static void requireValid(String org) {
        if (org != null && !isValid(org)) throw new IllegalArgumentException("organization invalide (a-z, 0-9 et -, 1 à 40 caractères)");
    }

    /** Organisation pour l'affichage : {@link #UNASSIGNED} à défaut. */
    public static String orUnassigned(String org) { return org == null ? UNASSIGNED : org; }

    /**
     * Vrai si une ressource d'organisation {@code org} (null : aucune) passe le filtre {@code filter} ;
     * un filtre null laisse tout passer, {@value #UNASSIGNED} ne garde que les ressources sans organisation.
     */
    public static boolean matches(String filter, String org) { return filter == null || filter.equals(orUnassigned(org)); }

    /** Vrai si {@code role} est un des rôles des clients. */
    public static boolean isCustomerRole(String role) { return role != null && ROLE_SET.contains(role); }
}
