package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** Track that produced a finding; written in lowercase in the JSON. */
public enum FindingKind {
    /** Check of the native executable ({@code N-*} rules). */
    @JsonProperty("native") NATIVE,
    /** Vulnerabilities of the dependencies. */
    @JsonProperty("deps") DEPS,
    /** SonarQube ({@code gluonify:GN*} rules and others). */
    @JsonProperty("sonar") SONAR,
    /** Conformity check before compilation ({@code R-*} rules). */
    @JsonProperty("conformity") CONFORMITY,
    /** API tests (Postman collections). */
    @JsonProperty("apitest") APITEST,
    /** Passive online checks. */
    @JsonProperty("passive") PASSIVE,
    /** Active ZAP scan. */
    @JsonProperty("zap") ZAP,
    /** Search-engine optimization of the sites. */
    @JsonProperty("seo") SEO;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }
}
