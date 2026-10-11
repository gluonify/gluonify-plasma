package io.gluonify.plasma.usage;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Grandeur d'une unité du catalogue (celle dans laquelle l'usage est compté, {@code metering.md} § 3.1) et grandeurs de facturation
 * ({@code per} d'une ligne de prix, {@code contracts.md} § 2.4) qui s'en déduisent, avec leur facteur de conversion : quantité facturée = quantité ÷ facteur.
 */
public enum Quantity {
    /** Mo × s (mémoire réservée ou utilisée intégrée dans le temps). */
    MB_SECONDS("MB-second", Map.of("MB-second", "1", "MB-hour", "3600", "GB-hour", "3686400")),
    /** Secondes (CPU, durée de build ou de requête). */
    SECONDS("second", Map.of("second", "1", "minute", "60", "hour", "3600")),
    /** Compteur (requêtes, documents, messages…). */
    COUNT("unit", Map.of("unit", "1", "thousand", "1000", "million", "1000000")),
    /** Octets (trafic, octets écrits) ; GB = 2³⁰ octets. */
    BYTES("byte", Map.of("byte", "1", "MB", "1048576", "GB", "1073741824")),
    /** Go × h (volume stocké intégré dans le temps). */
    GB_HOURS("GB-hour", Map.of("GB-hour", "1", "GB-month", "720")),
    /** Objet × s (domaine × s, connexion × s, entrée × s). */
    OBJECT_SECONDS("unit-second", Map.of("unit-second", "1", "unit-hour", "3600", "unit-month", "2592000"));

    private final String base;
    private final Map<String, BigDecimal> per;

    Quantity(String base, Map<String, String> per) {
        this.base = base;
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        per.forEach((k, v) -> m.put(k, new BigDecimal(v)));
        this.per = Collections.unmodifiableMap(m);
    }

    /** Nom de la grandeur de base (celle des enregistrements d'usage). */
    public String base() { return base; }

    /** Grandeurs de facturation admises et leur facteur. */
    public Map<String, BigDecimal> perUnits() { return per; }

    /** Vrai si {@code per} est une grandeur de facturation de cette grandeur. */
    public boolean accepts(String unit) { return unit != null && per.containsKey(unit); }

    /** Convertit une quantité de base dans la grandeur {@code unit} (précision de 34 chiffres, sans arrondi monétaire). */
    public BigDecimal convert(BigDecimal quantity, String unit) {
        BigDecimal f = per.get(unit);
        if (f == null) throw new IllegalArgumentException("grandeur " + unit + " incompatible avec " + base);
        return quantity.divide(f, MathContext.DECIMAL128);
    }
}
