package io.gluonify.plasma.usage;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import java.util.Collection;

/**
 * Ce qu'un composant sait attribuer à chaque application ({@code usage-mcp.md} § 3.3), à implémenter (bean CDI) par le composant qui le peut.
 * Sans implémentation, {@link UsageResource} répond {@code attribution: none}, état valide : le contrat porte sur {@code process} et sur la forme,
 * pas sur une attribution que le composant ne peut pas tenir. Lecture de compteurs ou de valeurs en cache seulement (jamais de parcours de disque).
 */
public interface UsageContributor {
    /** Les parts par application (CPU cumulé en secondes, mémoire en octets) ; vide si rien à attribuer. */
    Collection<AppShare> byApp();

    /** {@link UsageReport#MEASURED}, {@link UsageReport#ESTIMATED} ou {@link UsageReport#PRORATA} ; par défaut {@code estimated} (indicatif). */
    default String attribution() {
        return UsageReport.ESTIMATED;
    }
}
