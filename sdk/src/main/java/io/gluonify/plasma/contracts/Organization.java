package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.gluonify.plasma.Organizations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Une organisation (le client), objet de Higgs répliqué par Raft ({@code contracts.md} § 2.1), avec l'historique de ses versions de contrat.
 *
 * @param id          identifiant immuable ({@link Organizations#PATTERN}), ou {@value #DEFAULT} pour le contrat par défaut
 * @param name        nom d'affichage (1 à 120 caractères)
 * @param status      {@value #ACTIVE}, {@value #SUSPENDED} ou {@value #ARCHIVED}
 * @param externalRef référence externe libre (CRM), 120 caractères au plus
 * @param createdAt   instant de création (ms)
 * @param createdBy   auteur
 * @param versions    versions du contrat, dans l'ordre croissant de {@code version} ; jamais null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Organization(String id, String name, String status, String externalRef, Long createdAt, String createdBy, List<ContractVersion> versions) {
    /** Pseudo-organisation qui porte le contrat par défaut : il s'applique à toute organisation sans version propre. */
    public static final String DEFAULT = "_default";
    public static final String ACTIVE = "active";
    public static final String SUSPENDED = "suspended";
    public static final String ARCHIVED = "archived";
    public static final Set<String> STATUSES = Set.of(ACTIVE, SUSPENDED, ARCHIVED);
    public static final int MAX_NAME = 120;

    public Organization {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (status == null) status = ACTIVE;
    }

    /** Identifiant admis pour une organisation créée par l'API (le motif de Charm ; {@value #DEFAULT} n'en est pas un). */
    public static boolean validId(String id) { return Organizations.isValid(id); }

    /** Nom d'affichage valide : 1 à 120 caractères, sans caractère de contrôle. */
    public static boolean validName(String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME && name.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    public Organization withName(String n, String ref) { return new Organization(id, n, status, ref, createdAt, createdBy, versions); }

    public Organization withStatus(String s) { return new Organization(id, name, s, externalRef, createdAt, createdBy, versions); }

    public Organization withVersions(List<ContractVersion> v) { return new Organization(id, name, status, externalRef, createdAt, createdBy, v); }

    /** Copie avec une version ajoutée (ou remplacée si le numéro existe). */
    public Organization withVersion(ContractVersion v) {
        List<ContractVersion> out = new ArrayList<>();
        boolean replaced = false;
        for (ContractVersion x : versions) {
            if (x.version().equals(v.version())) {
                out.add(v);
                replaced = true;
            } else {
                out.add(x);
            }
        }
        if (!replaced) out.add(v);
        return withVersions(out);
    }

    /** La version de ce numéro, ou null. */
    public ContractVersion version(int v) {
        for (ContractVersion x : versions) if (x.version() == v) return x;
        return null;
    }

    /** Prochain numéro de version. */
    public int nextVersion() {
        int max = 0;
        for (ContractVersion x : versions) max = Math.max(max, x.version());
        return max + 1;
    }

    public boolean suspended() { return SUSPENDED.equals(status); }

    public boolean archived() { return ARCHIVED.equals(status); }
}
