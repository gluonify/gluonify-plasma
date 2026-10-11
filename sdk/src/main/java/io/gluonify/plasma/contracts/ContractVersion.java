package io.gluonify.plasma.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Une version du contrat d'une organisation ({@code contracts.md} § 2.2), immuable une fois écrite (seuls l'annulation d'une version à venir et
 * l'approbation d'une version en attente la complètent). L'état ({@code scheduled}, {@code active}, {@code superseded}, {@code cancelled}, {@code pending}) est
 * calculé ({@link Contracts#state}), jamais enregistré.
 *
 * @param version       entier croissant par organisation, attribué par Higgs
 * @param effectiveFrom instant (ms UTC) d'effet
 * @param createdAt     instant de création
 * @param createdBy     sujet du jeton de l'auteur
 * @param note          raison (1 à 500 caractères)
 * @param fingerprint   {@code sha256:<hex>} du JSON canonique des termes ({@link Contracts#fingerprint})
 * @param terms         les termes
 * @param cancelledAt   annulée (avant son effet) à cet instant ; null sinon
 * @param cancelledBy   auteur de l'annulation
 * @param pending       vrai : en attente d'une approbation (quatre yeux) ; jamais null
 * @param approvedAt    instant de l'approbation
 * @param approvedBy    sujet qui l'a approuvée (jamais l'auteur)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record ContractVersion(Integer version, Long effectiveFrom, Long createdAt, String createdBy, String note, String fingerprint, ContractTerms terms,
                              Long cancelledAt, String cancelledBy, Boolean pending, Long approvedAt, String approvedBy) {
    public static final int MAX_NOTE = 500;

    public ContractVersion {
        if (pending == null) pending = Boolean.FALSE;
    }

    /** Version annulée. */
    public boolean cancelled() { return cancelledAt != null; }

    /** En attente d'approbation (jamais effective tant qu'elle n'est pas approuvée). */
    public boolean awaitingApproval() { return Boolean.TRUE.equals(pending) && approvedAt == null; }

    public ContractVersion cancel(long at, String by) {
        return new ContractVersion(version, effectiveFrom, createdAt, createdBy, note, fingerprint, terms, at, by, pending, approvedAt, approvedBy);
    }

    public ContractVersion approve(long at, String by) {
        return new ContractVersion(version, effectiveFrom, createdAt, createdBy, note, fingerprint, terms, cancelledAt, cancelledBy, pending, at, by);
    }
}
