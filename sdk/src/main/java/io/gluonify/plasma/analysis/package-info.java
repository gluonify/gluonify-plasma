/**
 * Format commun des constats d'analyse (spécification {@code gluonify-codex/analysis.md} § 3.6), partagé par Bup, le tracker et Prism.
 *
 * <p>Un constat ne contient <strong>aucune phrase</strong> : un code de règle stable ({@code N-ELF}, {@code R-SECRET}, {@code GN04}…) et des
 * arguments. L'interface (Prism) traduit le code dans les cinq langues (clés {@code analysis.rules.<code>.title|why|fix}).
 * Les types lus ignorent les propriétés inconnues : un champ ajouté plus tard ne casse pas un ancien lecteur.
 */
package io.gluonify.plasma.analysis;
