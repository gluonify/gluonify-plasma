/**
 * Contrats des organisations ({@code gluonify-codex/contracts.md}) : organisation, versions de contrat immuables, termes (services, quotas, répartition,
 * tarification, budget), listes de prix, violations, et les fonctions pures partagées par Higgs, le calorimètre et Prism : version effective
 * ({@link io.gluonify.plasma.contracts.Contracts#effective}), empreinte canonique ({@link io.gluonify.plasma.contracts.Contracts#fingerprint}), validation
 * ({@link io.gluonify.plasma.contracts.TermsValidator}) et valorisation ({@link io.gluonify.plasma.contracts.Pricing}).
 * Les types lus ignorent les propriétés inconnues ; les booléens sont des {@code Boolean} (un instantané Raft qui les omet se relit).
 */
package io.gluonify.plasma.contracts;
