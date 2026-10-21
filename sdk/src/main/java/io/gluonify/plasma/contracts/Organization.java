package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.gluonify.plasma.Organizations;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * An organization (the customer), a Higgs object replicated by Raft ({@code contracts.md} § 2.1), with the history of its contract versions.
 *
 * @param id          immutable identifier ({@link Organizations#PATTERN}), or {@value #DEFAULT} for the default contract
 * @param name        display name (1 to 120 characters)
 * @param status      {@value #ACTIVE}, {@value #STATUS_SUSPENDED} or {@value #STATUS_ARCHIVED}
 * @param externalRef free external reference (CRM), at most 120 characters
 * @param createdAt   creation instant (ms)
 * @param createdBy   author
 * @param versions    contract versions, in increasing order of {@code version}; never null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Organization(String id, String name, String status, String externalRef, Long createdAt, String createdBy, List<ContractVersion> versions) {
    /** Pseudo-organization that carries the default contract: it applies to any organization without a version of its own. */
    public static final String DEFAULT = "_default";
    public static final String ACTIVE = "active";
    public static final String STATUS_SUSPENDED = "suspended";
    /** @deprecated renamed {@link #STATUS_SUSPENDED} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final String SUSPENDED = STATUS_SUSPENDED;
    public static final String STATUS_ARCHIVED = "archived";
    /** @deprecated renamed {@link #STATUS_ARCHIVED} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final String ARCHIVED = STATUS_ARCHIVED;
    public static final Set<String> STATUSES = Set.of(ACTIVE, STATUS_SUSPENDED, STATUS_ARCHIVED);
    public static final int MAX_NAME = 120;

    public Organization {
        versions = versions == null ? List.of() : List.copyOf(versions);
        if (status == null) status = ACTIVE;
    }

    /** Identifier accepted for an organization created through the API (Charm's pattern; {@value #DEFAULT} is not one). */
    public static boolean validId(final String id) { return Organizations.isValid(id); }

    /** Valid display name: 1 to 120 characters, no control character. */
    public static boolean validName(final String name) {
        return name != null && !name.isBlank() && name.length() <= MAX_NAME && name.chars().noneMatch(c -> c < 0x20 || c == 0x7f);
    }

    public Organization withName(final String n, final String ref) { return new Organization(id, n, status, ref, createdAt, createdBy, versions); }

    public Organization withStatus(final String s) { return new Organization(id, name, s, externalRef, createdAt, createdBy, versions); }

    public Organization withVersions(final List<ContractVersion> v) { return new Organization(id, name, status, externalRef, createdAt, createdBy, v); }

    /** Copy with a version added (or replaced if the number exists). */
    public Organization withVersion(final ContractVersion v) {
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

    /** The version with this number, or null. */
    public ContractVersion version(final int v) {
        for (ContractVersion x : versions) if (x.version() == v) return x;
        return null;
    }

    /** Next version number. */
    public int nextVersion() {
        int max = 0;
        for (ContractVersion x : versions) max = Math.max(max, x.version());
        return max + 1;
    }

    public boolean suspended() { return STATUS_SUSPENDED.equals(status); }

    public boolean archived() { return STATUS_ARCHIVED.equals(status); }
}
