package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * A version of a plan: immutable, with its effective date, its author, its reason and its terms.
 *
 * @param billing {@value #BILLING_NONE} (nothing is valued nor invoiced: the {@code Developer} plan) or {@value #BILLING_USAGE} (valued with the terms' pricing)
 * @param terms   the preset terms (never a {@code plan} of their own)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanVersion(Integer version, Long effectiveFrom, Long createdAt, String createdBy, String note, String billing, ContractTerms terms) {
    public static final String BILLING_NONE = "none";
    public static final String BILLING_USAGE = "usage";

    /** True if nothing of an organization on this plan is invoiced. */
    public boolean free() { return BILLING_NONE.equals(billing); }
}
