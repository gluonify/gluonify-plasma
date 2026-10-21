package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Summary of a build analysis ({@code BuildRecord.analysis} at Bup, {@code analysis.md} § 3.6): an overall status and a summary per track,
 * {@code null} when the track does not exist yet. Absent fields are omitted from the JSON.
 *
 * @param status      the most serious of the tracks ({@link #overall})
 * @param nativeImage native track (written {@code native} in the JSON)
 * @param deps        dependencies track
 * @param sonar       Sonar track
 * @param findings    number of findings of the full report
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisSummary(AnalysisStatus status, @JsonProperty("native") NativeSummary nativeImage, DepsSummary deps, SonarSummary sonar, Integer findings) {
    public AnalysisSummary {
        if (findings == null) findings = 0;
        if (status == null) status = overall(nativeImage, deps, sonar);
    }

    /** Overall status: the most serious of the tracks present ({@link AnalysisStatus#SKIPPED} if there are none). */
    public static AnalysisStatus overall(final NativeSummary n, final DepsSummary d, final SonarSummary s) {
        return AnalysisStatus.worst(n == null ? null : n.status(), d == null ? null : d.status(), s == null ? null : s.status());
    }

    public AnalysisSummary withNative(final NativeSummary n) {
        return new AnalysisSummary(overall(n, deps, sonar), n, deps, sonar, findings);
    }

    public AnalysisSummary withFindings(final Integer count) {
        return new AnalysisSummary(status, nativeImage, deps, sonar, count);
    }

    /**
     * Native track.
     *
     * @param status          track status
     * @param reason          reason code when the track is {@code skipped} or {@code unavailable} ({@code disabled}, {@code no-output}…), otherwise absent
     * @param arch            architecture of the executable ({@code arm64}, {@code amd64})
     * @param sizeBytes       size of the executable
     * @param sizeDelta       relative difference from the last successful build of the same repository ({@code 0.03} = +3 %), absent without a previous build
     * @param glibc           most recent glibc version required ({@code 2.34}), absent for a static executable
     * @param graalvm         GraalVM version read from the executable or from the native-image output, if known
     * @param reflectionTypes types registered for reflection (native-image JSON output)
     * @param resources       included resources (same source)
     * @param startup         cold start of the new instances, known after the deployment
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NativeSummary(AnalysisStatus status, String reason, String arch, Long sizeBytes, Double sizeDelta, String glibc, String graalvm,
                                Integer reflectionTypes, Integer resources, Startup startup) {
        public NativeSummary {
            if (status == null) status = AnalysisStatus.OK;
        }

        public NativeSummary withStartup(final Startup s, final AnalysisStatus newStatus) {
            return new NativeSummary(newStatus, reason, arch, sizeBytes, sizeDelta, glibc, graalvm, reflectionTypes, resources, s);
        }
    }

    /**
     * Cold start of the instances deployed by the build.
     *
     * @param medianSeconds median of the total time ({@code total}) of the instances
     * @param worstSeconds  the longest
     * @param instances     number of instances measured
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Startup(Double medianSeconds, Double worstSeconds, Integer instances) {
    }

    /** Dependencies track (phase A2). {@code counts}: number of vulnerabilities per severity ({@code critical}, {@code high}…). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DepsSummary(AnalysisStatus status, String reason, Integer components, Db db, Map<String, Integer> counts) {
        public DepsSummary {
            if (status == null) status = AnalysisStatus.OK;
            counts = counts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }
    }

    /** Vulnerability database used: its date ({@code 2026-10-11}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Db(String date) {
    }

    /** Sonar track (phase A3). {@code gate}: verdict of the gate ({@code OK}, {@code ERROR}); {@code gluonify}: findings of the {@code gluonify:*} rules. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonarSummary(AnalysisStatus status, String reason, String projectKey, String gate, Map<String, Integer> counts, Integer gluonify) {
        public SonarSummary {
            if (status == null) status = AnalysisStatus.OK;
            counts = counts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }
    }
}
