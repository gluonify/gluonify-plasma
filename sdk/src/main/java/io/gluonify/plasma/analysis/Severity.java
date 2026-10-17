package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Locale;

/** Severity of a finding, from the most serious to the lightest; written in lowercase in the JSON. */
public enum Severity {
    @JsonProperty("blocker") BLOCKER,
    @JsonProperty("critical") CRITICAL,
    @JsonProperty("major") MAJOR,
    @JsonProperty("minor") MINOR,
    @JsonProperty("info") INFO;

    /** Name written in the JSON ({@code blocker}…). */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** True if this severity is at least as serious as {@code other} ({@code CRITICAL.atLeast(MAJOR)} is true). */
    public boolean atLeast(Severity other) {
        return ordinal() <= other.ordinal();
    }

    /** Reads the JSON name (case-insensitive); {@link IllegalArgumentException} if unknown. */
    public static Severity parse(String s) {
        if (s == null) throw new IllegalArgumentException("severity missing");
        return valueOf(s.trim().toUpperCase(Locale.ROOT));
    }
}
