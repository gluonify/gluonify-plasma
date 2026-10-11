package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Résumé d'une analyse de build ({@code BuildRecord.analysis} chez Bup, {@code analysis.md} § 3.6) : un état global et un résumé par volet,
 * {@code null} quand le volet n'existe pas encore. Les champs absents sont omis du JSON.
 *
 * @param status      le plus grave des volets ({@link #overall})
 * @param nativeImage volet natif (écrit {@code native} dans le JSON)
 * @param deps        volet des dépendances
 * @param sonar       volet Sonar
 * @param findings    nombre de constats du rapport complet
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnalysisSummary(AnalysisStatus status, @JsonProperty("native") NativeSummary nativeImage, DepsSummary deps, SonarSummary sonar, Integer findings) {
    public AnalysisSummary {
        if (findings == null) findings = 0;
        if (status == null) status = overall(nativeImage, deps, sonar);
    }

    /** État global : le plus grave des volets présents ({@link AnalysisStatus#SKIPPED} s'il n'y en a aucun). */
    public static AnalysisStatus overall(NativeSummary n, DepsSummary d, SonarSummary s) {
        return AnalysisStatus.worst(n == null ? null : n.status(), d == null ? null : d.status(), s == null ? null : s.status());
    }

    public AnalysisSummary withNative(NativeSummary n) {
        return new AnalysisSummary(overall(n, deps, sonar), n, deps, sonar, findings);
    }

    public AnalysisSummary withFindings(Integer count) {
        return new AnalysisSummary(status, nativeImage, deps, sonar, count);
    }

    /**
     * Volet natif.
     *
     * @param status          état du volet
     * @param reason          code de la raison quand le volet est {@code skipped} ou {@code unavailable} ({@code disabled}, {@code no-output}…), sinon absent
     * @param arch            architecture de l'exécutable ({@code arm64}, {@code amd64})
     * @param sizeBytes       taille de l'exécutable
     * @param sizeDelta       écart relatif avec le dernier build réussi du même dépôt ({@code 0.03} = +3 %), absent sans build précédent
     * @param glibc           version de glibc la plus récente exigée ({@code 2.34}), absente pour un exécutable statique
     * @param graalvm         version de GraalVM lue dans l'exécutable ou dans la sortie de native-image, si connue
     * @param reflectionTypes types enregistrés pour la réflexion (sortie JSON de native-image)
     * @param resources       ressources incluses (même source)
     * @param startup         démarrage à froid des nouvelles instances, connu après le déploiement
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NativeSummary(AnalysisStatus status, String reason, String arch, Long sizeBytes, Double sizeDelta, String glibc, String graalvm,
                                Integer reflectionTypes, Integer resources, Startup startup) {
        public NativeSummary {
            if (status == null) status = AnalysisStatus.OK;
        }

        public NativeSummary withStartup(Startup s, AnalysisStatus newStatus) {
            return new NativeSummary(newStatus, reason, arch, sizeBytes, sizeDelta, glibc, graalvm, reflectionTypes, resources, s);
        }
    }

    /**
     * Démarrage à froid des instances déployées par le build.
     *
     * @param medianSeconds médiane du temps total ({@code total}) des instances
     * @param worstSeconds  le plus long
     * @param instances     nombre d'instances mesurées
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Startup(Double medianSeconds, Double worstSeconds, Integer instances) {
    }

    /** Volet des dépendances (phase A2). {@code counts} : nombre de vulnérabilités par sévérité ({@code critical}, {@code high}…). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DepsSummary(AnalysisStatus status, String reason, Integer components, Db db, Map<String, Integer> counts) {
        public DepsSummary {
            if (status == null) status = AnalysisStatus.OK;
            counts = counts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }
    }

    /** Base de vulnérabilités utilisée : sa date ({@code 2026-10-11}). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Db(String date) {
    }

    /** Volet Sonar (phase A3). {@code gate} : verdict de la porte ({@code OK}, {@code ERROR}) ; {@code gluonify} : constats des règles {@code gluonify:*}. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SonarSummary(AnalysisStatus status, String reason, String projectKey, String gate, Map<String, Integer> counts, Integer gluonify) {
        public SonarSummary {
            if (status == null) status = AnalysisStatus.OK;
            counts = counts == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(counts));
        }
    }
}
