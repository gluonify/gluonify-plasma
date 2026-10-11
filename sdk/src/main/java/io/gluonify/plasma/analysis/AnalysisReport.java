package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rapport complet d'une analyse : le contenu de {@code analysis.json} (dossier du build chez Bup) et la réponse de
 * {@code GET /v1/builds/{id}/analysis}. Les constats sont triés par sévérité (les plus graves d'abord), puis par volet et par règle.
 *
 * @param v        version du format ({@value Finding#VERSION})
 * @param buildId  build analysé, absent hors de Bup
 * @param summary  résumé (le même que {@code BuildRecord.analysis})
 * @param findings constats, jamais {@code null}, non modifiable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisReport(Integer v, String buildId, AnalysisSummary summary, List<Finding> findings) {
    /** Ordre d'affichage : sévérité, bloquant d'abord, volet, règle, identifiant. */
    public static final Comparator<Finding> ORDER = Comparator.comparing(Finding::severity)
            .thenComparing(f -> !f.blocking())
            .thenComparing(Finding::kind)
            .thenComparing(Finding::rule)
            .thenComparing(f -> f.id() == null ? "" : f.id());

    public AnalysisReport {
        if (v == null || v == 0) v = Finding.VERSION;
        List<Finding> sorted = new ArrayList<>(findings == null ? List.of() : findings);
        sorted.sort(ORDER);
        findings = List.copyOf(sorted);
        if (summary != null && summary.findings() != findings.size()) summary = summary.withFindings(findings.size());
    }

    /** Vrai si au moins un constat ouvert est bloquant. */
    public boolean blocked() {
        return findings.stream().anyMatch(f -> f.blocking() && f.status() == FindingStatus.OPEN);
    }
}
