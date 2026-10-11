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
 * Un constat d'analyse (format {@value #VERSION} de {@code analysis.md} § 3.6). <strong>Aucune phrase</strong> : {@code rule} est un code stable
 * et {@code args} ses arguments (des chaînes, interpolées par la traduction de l'interface). {@code location} est relatif au dépôt.
 *
 * @param v        version du format ({@value #VERSION}) ; absente à la lecture : {@value #VERSION}
 * @param id       {@code sha256(kind|target|rule|location)} en hexadécimal : stable d'un passage à l'autre (voir {@link #idOf})
 * @param kind     volet
 * @param rule     code de règle : {@code N-ELF}, {@code R-SECRET}, {@code GN04}, {@code SEO-H1-IN-TEXT}…
 * @param severity sévérité
 * @param blocking vrai si le constat fait échouer le build (ou bloque la promotion) ; absent : faux, jamais {@code null}
 * @param location endroit, ou {@code null}
 * @param args     arguments, jamais {@code null} (au minimum vide), ordre conservé, non modifiable
 * @param ref      référence externe facultative (identifiant d'avis {@code GHSA-…}, clé Sonar)
 * @param status   {@code open} par défaut
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Finding(Integer v, String id, FindingKind kind, String rule, Severity severity, Boolean blocking, Location location,
                      Map<String, String> args, String ref, FindingStatus status) {
    public static final int VERSION = 1;
    /** Code de règle : majuscules, chiffres et tirets, avec un préfixe facultatif {@code depot:} (règles Sonar). */
    public static final Pattern RULE = Pattern.compile("(?:[a-z][a-z0-9-]{0,39}:)?[A-Z][A-Z0-9]*(?:-[A-Z0-9]+)*");
    /** Nom d'argument : identifiant simple, utilisable tel quel dans une chaîne traduite ({@code {type}}). */
    public static final Pattern ARG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]{0,63}");
    /** Longueur maximale d'une valeur d'argument. */
    public static final int MAX_ARG_LENGTH = 1024;
    public static final int MAX_ARGS = 32;

    public Finding {
        if (v == null || v == 0) v = VERSION;
        if (blocking == null) blocking = Boolean.FALSE;
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(severity, "severity");
        if (rule == null || !RULE.matcher(rule).matches()) throw new IllegalArgumentException("code de règle invalide : " + rule);
        args = copyArgs(args);
        if (status == null) status = FindingStatus.OPEN;
    }

    /** Constat ouvert, identifiant calculé à partir de la cible ({@code target} : build, application, site… ; jamais écrit dans le constat). */
    public static Finding open(FindingKind kind, String target, String rule, Severity severity, boolean blocking, Location location, Map<String, String> args) {
        return new Finding(VERSION, idOf(kind, target, rule, location), kind, rule, severity, blocking, location, args, null, FindingStatus.OPEN);
    }

    public Finding withRef(String newRef) {
        return new Finding(v, id, kind, rule, severity, blocking, location, args, newRef, status);
    }

    public Finding withStatus(FindingStatus newStatus) {
        return new Finding(v, id, kind, rule, severity, blocking, location, args, ref, newStatus);
    }

    /** {@code sha256(kind|target|rule|location)} en hexadécimal minuscule ; {@code target} et {@code location} absents valent la chaîne vide. */
    public static String idOf(FindingKind kind, String target, String rule, Location location) {
        String s = kind.wire() + "|" + (target == null ? "" : target) + "|" + rule + "|" + (location == null ? "" : location.key());
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Une valeur d'argument tronquée à {@link #MAX_ARG_LENGTH} caractères, {@code null} donnant la chaîne vide. */
    public static String arg(Object value) {
        String s = value == null ? "" : String.valueOf(value);
        return s.length() > MAX_ARG_LENGTH ? s.substring(0, MAX_ARG_LENGTH) : s;
    }

    private static Map<String, String> copyArgs(Map<String, String> in) {
        if (in == null || in.isEmpty()) return Map.of();
        if (in.size() > MAX_ARGS) throw new IllegalArgumentException("plus de " + MAX_ARGS + " arguments");
        Map<String, String> out = new LinkedHashMap<>();
        in.forEach((k, val) -> {
            if (k == null || !ARG.matcher(k).matches()) throw new IllegalArgumentException("nom d'argument invalide : " + k);
            out.put(k, arg(val));
        });
        return Collections.unmodifiableMap(out);
    }
}
