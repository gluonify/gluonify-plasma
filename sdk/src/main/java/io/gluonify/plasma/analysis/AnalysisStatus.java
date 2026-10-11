package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * État d'un volet d'analyse ou de l'analyse entière. L'ordre de déclaration est celui de la gravité croissante :
 * {@link #worst} garde le plus grave, un volet {@link #SKIPPED} ne pèse pas.
 */
public enum AnalysisStatus {
    /** Volet non exécuté (désactivé, ou sans objet : dépôt sans organisation pour Sonar…). */
    @JsonProperty("skipped") SKIPPED,
    @JsonProperty("ok") OK,
    /** Résultat attendu plus tard (verdict Sonar asynchrone). */
    @JsonProperty("pending") PENDING,
    /** Outillage en panne ou sortie absente : avertissement, jamais un échec du build (sauf politique {@code require}). */
    @JsonProperty("unavailable") UNAVAILABLE,
    @JsonProperty("warning") WARNING,
    /** Au moins un constat bloquant : le build échoue. */
    @JsonProperty("blocked") BLOCKED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Le plus grave des deux ({@code null} ignoré). */
    public static AnalysisStatus worst(AnalysisStatus a, AnalysisStatus b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Le plus grave de la liste ; {@link #SKIPPED} si elle est vide ou ne contient que des {@code null}. */
    public static AnalysisStatus worst(AnalysisStatus... all) {
        AnalysisStatus w = null;
        for (AnalysisStatus s : all) w = worst(w, s);
        return w == null ? SKIPPED : w;
    }
}
