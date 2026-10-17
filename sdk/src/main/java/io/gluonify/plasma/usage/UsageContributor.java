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

    /**
     * Same thing, given the process as {@link UsageResource} has just measured it: for a component that splits its own totals pro rata of some measured quantity
     * (bytes, requests) without reading {@code /proc} again. Defaults to {@link #byApp()}.
     */
    default Collection<AppShare> byApp(final UsageReport.Process process) {
        return byApp();
    }

    /**
     * The bytes this component stores in total (disk, measured or from its last snapshot; read from a cache, never a disk walk), the {@code storageBytes} of the
     * report; null (default) when it stores nothing. Reported even when {@code byApp} is empty ({@code attribution: none}). Rows may carry their own share
     * ({@link AppShare#storageBytes()}), whose sum must not exceed this total.
     */
    default Long storageBytes() {
        return null;
    }

    /** {@link UsageReport#MEASURED}, {@link UsageReport#ESTIMATED} ou {@link UsageReport#PRORATA} ; par défaut {@code estimated} (indicatif). */
    default String attribution() {
        return UsageReport.ESTIMATED;
    }
}
