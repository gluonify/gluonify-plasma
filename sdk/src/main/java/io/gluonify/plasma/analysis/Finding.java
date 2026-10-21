package io.gluonify.plasma.analysis;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * An analysis finding (format {@value #CURRENT_VERSION} of {@code analysis.md} § 3.6). <strong>No sentence</strong>: {@code rule} is a stable code
 * and {@code args} its arguments (strings, interpolated by the interface translation). {@code location} is relative to the repository.
 *
 * @param v        format version ({@value #CURRENT_VERSION}); absent when read: {@value #CURRENT_VERSION}
 * @param id       {@code sha256(kind|target|rule|location)} in hexadecimal: stable from one run to the next (see {@link #idOf})
 * @param kind     track
 * @param rule     rule code: {@code N-ELF}, {@code R-SECRET}, {@code GN04}, {@code SEO-H1-IN-TEXT}…
 * @param severity severity
 * @param blocking true if the finding fails the build (or blocks the promotion); absent: false, never {@code null}
 * @param location place, or {@code null}
 * @param args     arguments, never {@code null} (at least empty), order kept, unmodifiable
 * @param ref      optional external reference (advisory identifier {@code GHSA-…}, Sonar key)
 * @param status   {@code open} by default
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(Integer v, String id, FindingKind kind, String rule, Severity severity, Boolean blocking, Location location,
                      Map<String, String> args, String ref, FindingStatus status) {
    public static final int CURRENT_VERSION = 1;
    /** @deprecated renamed {@link #CURRENT_VERSION} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final int VERSION = CURRENT_VERSION;
    /** Rule code: uppercase letters, digits and hyphens, with an optional {@code repository:} prefix (Sonar rules). */
    public static final Pattern RULE_PATTERN = Pattern.compile("(?:[a-z][a-z0-9-]{0,39}:)?[A-Z][A-Z0-9]*+(?:-[A-Z0-9]++)*+");
    /** @deprecated renamed {@link #RULE_PATTERN} (SonarQube S1845); removed in the version after 0.7.2. */
    @Deprecated(forRemoval = true)
    public static final Pattern RULE = RULE_PATTERN;
    /** Argument name: simple identifier, usable as is in a translated string ({@code {type}}). */
    public static final Pattern ARG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    /** Maximum length of an argument value. */
    public static final int MAX_ARG_LENGTH = 1024;
    public static final int MAX_ARGS = 32;

    public Finding {
        if (v == null || v == 0) v = CURRENT_VERSION;
        if (blocking == null) blocking = Boolean.FALSE;
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        if (rule == null || !RULE_PATTERN.matcher(rule).matches()) throw new IllegalArgumentException("invalid rule code: " + rule);
        args = copyArgs(args);
        if (status == null) status = FindingStatus.OPEN;
    }

    /** Open finding, identifier computed from the target ({@code target}: build, application, site…; never written in the finding). */
    public static Finding open(final FindingKind kind, final String target, final String rule, final Severity severity, final boolean blocking, final Location location, final Map<String, String> args) {
        return new Finding(CURRENT_VERSION, idOf(kind, target, rule, location), kind, rule, severity, blocking, location, args, null, FindingStatus.OPEN);
    }

    public Finding withRef(final String newRef) {
        return new Finding(v, id, kind, rule, severity, blocking, location, args, newRef, status);
    }

    public Finding withStatus(final FindingStatus newStatus) {
        return new Finding(v, id, kind, rule, severity, blocking, location, args, ref, newStatus);
    }

    /** {@code sha256(kind|target|rule|location)} in lowercase hexadecimal; absent {@code target} and {@code location} count as the empty string. */
    public static String idOf(final FindingKind kind, final String target, final String rule, final Location location) {
        String s = kind.wire() + "|" + (target == null ? "" : target) + "|" + rule + "|" + (location == null ? "" : location.key());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** An argument value truncated to {@link #MAX_ARG_LENGTH} characters, {@code null} giving the empty string. */
    public static String arg(final Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return s.length() > MAX_ARG_LENGTH ? s.substring(0, MAX_ARG_LENGTH) : s;
    }

    private static Map<String, String> copyArgs(final Map<String, String> in) {
        if (in == null || in.isEmpty()) return Map.of();
        if (in.size() > MAX_ARGS) throw new IllegalArgumentException("more than " + MAX_ARGS + " arguments");
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, val) -> {
            if (k == null || !ARG.matcher(k).matches()) throw new IllegalArgumentException("invalid argument name: " + k);
            out.put(k, arg(val));
        });
        return Collections.unmodifiableMap(out);
    }
}
