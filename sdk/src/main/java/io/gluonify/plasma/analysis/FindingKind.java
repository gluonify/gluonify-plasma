package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** Volet qui a produit un constat ; écrit en minuscules dans le JSON. */
public enum FindingKind {
    /** Vérification de l'exécutable natif (règles {@code N-*}). */
    @JsonProperty("native") NATIVE,
    /** Vulnérabilités des dépendances. */
    @JsonProperty("deps") DEPS,
    /** SonarQube (règles {@code gluonify:GN*} et autres). */
    @JsonProperty("sonar") SONAR,
    /** Contrôle de conformité avant la compilation (règles {@code R-*}). */
    @JsonProperty("conformity") CONFORMITY,
    /** Tests d'API (collections Postman). */
    @JsonProperty("apitest") APITEST,
    /** Contrôles passifs en ligne. */
    @JsonProperty("passive") PASSIVE,
    /** Scan actif ZAP. */
    @JsonProperty("zap") ZAP,
    /** Référencement des sites. */
    @JsonProperty("seo") SEO;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
