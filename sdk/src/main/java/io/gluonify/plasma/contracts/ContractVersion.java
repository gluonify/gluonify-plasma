package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A version of an organization's contract ({@code contracts.md} § 2.2), immutable once written (only the cancellation of an upcoming version and
 * the approval of a pending version complete it). The state ({@code scheduled}, {@code active}, {@code superseded}, {@code cancelled}, {@code pending}) is
 * computed ({@link Contracts#state}), never stored.
 *
 * @param version       integer increasing per organization, assigned by Higgs
 * @param effectiveFrom effective instant (ms UTC)
 * @param createdAt     creation instant
 * @param createdBy     subject of the author's token
 * @param note          reason (1 to 500 characters)
 * @param fingerprint   {@code sha256:<hex>} of the canonical JSON of the terms ({@link Contracts#fingerprint})
 * @param terms         the terms
 * @param cancelledAt   cancelled (before taking effect) at this instant; null otherwise
 * @param cancelledBy   author of the cancellation
 * @param pending       true: awaiting an approval (four eyes); never null
 * @param approvedAt    approval instant
 * @param approvedBy    subject who approved it (never the author)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractVersion(Integer version, Long effectiveFrom, Long createdAt, String createdBy, String note, String fingerprint, ContractTerms terms,
                              Long cancelledAt, String cancelledBy, Boolean pending, Long approvedAt, String approvedBy) {
    public static final int MAX_NOTE = 500;

    public ContractVersion {
        if (pending == null) pending = Boolean.FALSE;
    }

    /** Cancelled version. */
    public boolean cancelled() { return cancelledAt != null; }

    /** Awaiting approval (never effective until approved). */
    public boolean awaitingApproval() { return Boolean.TRUE.equals(pending) && approvedAt == null; }

    public ContractVersion cancel(final long at, final String by) {
        return new ContractVersion(version, effectiveFrom, createdAt, createdBy, note, fingerprint, terms, at, by, pending, approvedAt, approvedBy);
    }

    public ContractVersion approve(final long at, final String by) {
        return new ContractVersion(version, effectiveFrom, createdAt, createdBy, note, fingerprint, terms, cancelledAt, cancelledBy, pending, at, by);
    }
}
