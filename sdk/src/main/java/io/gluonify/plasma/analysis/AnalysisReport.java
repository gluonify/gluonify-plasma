package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Full report of an analysis: the content of {@code analysis.json} (build directory at Bup) and the response of
 * {@code GET /v1/builds/{id}/analysis}. Findings are sorted by severity (most serious first), then by track and by rule.
 *
 * @param v        format version ({@value Finding#VERSION})
 * @param buildId  analysed build, absent outside Bup
 * @param summary  summary (the same as {@code BuildRecord.analysis})
 * @param findings findings, never {@code null}, unmodifiable
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisReport(Integer v, String buildId, AnalysisSummary summary, List<Finding> findings) {
    /** Display order: severity, blocking first, track, rule, identifier. */
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

    /** True if at least one open finding is blocking. */
    public boolean blocked() {
        return findings.stream().anyMatch(f -> f.blocking() && f.status() == FindingStatus.OPEN);
    }
}
