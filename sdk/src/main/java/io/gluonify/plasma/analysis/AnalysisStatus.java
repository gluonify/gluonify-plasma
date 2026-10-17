package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/**
 * Status of an analysis track or of the whole analysis. The declaration order is that of increasing severity:
 * {@link #worst} keeps the most serious, a {@link #SKIPPED} track does not count.
 */
public enum AnalysisStatus {
    /** Track not run (disabled, or not applicable: repository without an organization for Sonar…). */
    @JsonProperty("skipped") SKIPPED,
    @JsonProperty("ok") OK,
    /** Result expected later (asynchronous Sonar verdict). */
    @JsonProperty("pending") PENDING,
    /** Tooling down or output missing: warning, never a build failure (except with the {@code require} policy). */
    @JsonProperty("unavailable") UNAVAILABLE,
    @JsonProperty("warning") WARNING,
    /** At least one blocking finding: the build fails. */
    @JsonProperty("blocked") BLOCKED;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The more serious of the two ({@code null} ignored). */
    public static AnalysisStatus worst(AnalysisStatus a, AnalysisStatus b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** The most serious of the list; {@link #SKIPPED} if it is empty or contains only {@code null}. */
    public static AnalysisStatus worst(AnalysisStatus... all) {
        AnalysisStatus w = null;
        for (AnalysisStatus s : all) w = worst(w, s);
        return w == null ? SKIPPED : w;
    }
}
