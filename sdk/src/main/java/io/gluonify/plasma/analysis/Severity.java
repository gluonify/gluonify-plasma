package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** Sévérité d'un constat, de la plus grave à la plus légère ; écrite en minuscules dans le JSON. */
public enum Severity {
    @JsonProperty("blocker") BLOCKER,
    @JsonProperty("critical") CRITICAL,
    @JsonProperty("major") MAJOR,
    @JsonProperty("minor") MINOR,
    @JsonProperty("info") INFO;

    /** Nom écrit dans le JSON ({@code blocker}…). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Vrai si cette sévérité est au moins aussi grave que {@code other} ({@code CRITICAL.atLeast(MAJOR)} est vrai). */
    public boolean atLeast(Severity other) {
        return ordinal() <= other.ordinal();
    }

    /** Lit le nom du JSON (casse indifférente) ; {@link IllegalArgumentException} si inconnu. */
    public static Severity parse(String s) {
        if (s == null) throw new IllegalArgumentException("sévérité absente");
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
