package io.gluonify.plasma.analysis;

import java.util.List;

/**
 * Service units of the analyses ({@code analysis.md} § 10), in the closed catalogue of {@code metering.md} § 3.1: name {@code <service>.<unit>}.
 * The dimension values are enumerated here, never free.
 */
public final class AnalysisUnits {
    private AnalysisUnits() {
    }

    /** One per track run at the end of the ANALYZING step ({@code skipped} and {@code unavailable} do not count); dimension {@link #DIM_KIND}. */
    public static final String BUP_ANALYSIS_RUNS = "bup.analysis.runs";
    /** Duration of the ANALYZING phase, in seconds, deducted from {@code bup.build.seconds}. */
    public static final String BUP_ANALYSIS_SECONDS = "bup.analysis.seconds";
    /** Runs of the API_TEST state. */
    public static final String BUP_APITESTS_RUNS = "bup.apitests.runs";
    /** Requests sent by the API tests. */
    public static final String BUP_APITESTS_REQUESTS = "bup.apitests.requests";
    /** Container jobs, in seconds, attributed to geiger, the site-analysis service ({@code internal: true}); dimension {@link #DIM_KIND}. */
    public static final String BUP_JOBS_SECONDS = "bup.jobs.seconds";
    /** Passive checks; dimension {@link #DIM_KIND}. */
    public static final String GEIGER_CHECKS_RUNS = "geiger.checks.runs";
    public static final String GEIGER_SEO_RUNS = "geiger.seo.runs";
    public static final String GEIGER_SEO_PAGES = "geiger.seo.pages";
    /** ZAP scans; dimension {@link #DIM_MODE}. */
    public static final String GEIGER_ZAP_RUNS = "geiger.zap.runs";
    public static final String GEIGER_ZAP_REQUESTS = "geiger.zap.requests";

    public static final String DIM_KIND = "kind";
    public static final String DIM_MODE = "mode";

    /** Values of {@code kind} for {@link #BUP_ANALYSIS_RUNS}. */
    public static final List<String> ANALYSIS_KINDS = List.of("native", "deps", "sonar");
    /** Values of {@code kind} for {@link #GEIGER_CHECKS_RUNS}. */
    public static final List<String> CHECK_KINDS = List.of("cert", "tls", "headers", "files", "endpoints");
    /** Values of {@code mode} for {@link #GEIGER_ZAP_RUNS}. */
    public static final List<String> ZAP_MODES = List.of("baseline", "api", "full");
    /** Values of {@code kind} for {@link #BUP_JOBS_SECONDS}. */
    public static final List<String> JOB_KINDS = List.of("zap", "render", "apitest");

    /** All the units, for the catalogue. */
    public static final List<String> ALL = List.of(BUP_ANALYSIS_RUNS, BUP_ANALYSIS_SECONDS, BUP_APITESTS_RUNS, BUP_APITESTS_REQUESTS, BUP_JOBS_SECONDS,
            GEIGER_CHECKS_RUNS, GEIGER_SEO_RUNS, GEIGER_SEO_PAGES, GEIGER_ZAP_RUNS, GEIGER_ZAP_REQUESTS);
}
