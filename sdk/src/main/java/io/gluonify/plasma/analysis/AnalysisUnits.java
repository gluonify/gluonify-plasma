package io.gluonify.plasma.analysis;

import java.util.List;

/**
 * Unités de service des analyses ({@code analysis.md} § 10), au catalogue fermé de {@code metering.md} § 3.1 : nom {@code <service>.<unité>}.
 * Les valeurs des dimensions sont énumérées ici, jamais libres.
 */
public final class AnalysisUnits {
    private AnalysisUnits() {
    }

    /** Une par volet exécuté à la fin de l'étape ANALYZING ({@code skipped} et {@code unavailable} ne comptent pas) ; dimension {@link #DIM_KIND}. */
    public static final String BUP_ANALYSIS_RUNS = "bup.analysis.runs";
    /** Durée de la phase ANALYZING, en secondes, retirée de {@code bup.build.seconds}. */
    public static final String BUP_ANALYSIS_SECONDS = "bup.analysis.seconds";
    /** Exécutions de l'état API_TEST. */
    public static final String BUP_APITESTS_RUNS = "bup.apitests.runs";
    /** Requêtes envoyées par les tests d'API. */
    public static final String BUP_APITESTS_REQUESTS = "bup.apitests.requests";
    /** Travaux en conteneur, en secondes, attribués au tracker ({@code internal: true}) ; dimension {@link #DIM_KIND}. */
    public static final String BUP_JOBS_SECONDS = "bup.jobs.seconds";
    /** Contrôles passifs ; dimension {@link #DIM_KIND}. */
    public static final String TRACKER_CHECKS_RUNS = "tracker.checks.runs";
    public static final String TRACKER_SEO_RUNS = "tracker.seo.runs";
    public static final String TRACKER_SEO_PAGES = "tracker.seo.pages";
    /** Scans ZAP ; dimension {@link #DIM_MODE}. */
    public static final String TRACKER_ZAP_RUNS = "tracker.zap.runs";
    public static final String TRACKER_ZAP_REQUESTS = "tracker.zap.requests";

    public static final String DIM_KIND = "kind";
    public static final String DIM_MODE = "mode";

    /** Valeurs de {@code kind} de {@link #BUP_ANALYSIS_RUNS}. */
    public static final List<String> ANALYSIS_KINDS = List.of("native", "deps", "sonar");
    /** Valeurs de {@code kind} de {@link #TRACKER_CHECKS_RUNS}. */
    public static final List<String> CHECK_KINDS = List.of("cert", "tls", "headers", "files", "endpoints");
    /** Valeurs de {@code mode} de {@link #TRACKER_ZAP_RUNS}. */
    public static final List<String> ZAP_MODES = List.of("baseline", "api", "full");
    /** Valeurs de {@code kind} de {@link #BUP_JOBS_SECONDS}. */
    public static final List<String> JOB_KINDS = List.of("zap", "render", "apitest");

    /** Toutes les unités, pour le catalogue. */
    public static final List<String> ALL = List.of(BUP_ANALYSIS_RUNS, BUP_ANALYSIS_SECONDS, BUP_APITESTS_RUNS, BUP_APITESTS_REQUESTS, BUP_JOBS_SECONDS,
            TRACKER_CHECKS_RUNS, TRACKER_SEO_RUNS, TRACKER_SEO_PAGES, TRACKER_ZAP_RUNS, TRACKER_ZAP_REQUESTS);
}
