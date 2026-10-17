/**
 * Organization contracts ({@code gluonify-codex/contracts.md}): organization, immutable contract versions, terms (services, quotas, spread,
 * pricing, budget), price lists, violations, and the pure functions shared by Higgs, the calorimeter and Prism: effective version
 * ({@link io.gluonify.plasma.contracts.Contracts#effective}), canonical fingerprint ({@link io.gluonify.plasma.contracts.Contracts#fingerprint}), validation
 * ({@link io.gluonify.plasma.contracts.TermsValidator}) and pricing ({@link io.gluonify.plasma.contracts.Pricing}).
 * Types that are read ignore unknown properties; booleans are {@code Boolean} (a Raft snapshot that omits them can still be read back).
 */
package io.gluonify.plasma.contracts;
