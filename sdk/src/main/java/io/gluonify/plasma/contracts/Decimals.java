package io.gluonify.plasma.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;

/** Montants décimaux en chaînes ({@code contracts.md} § 2.4) : jamais de {@code double}. */
public final class Decimals {
    private Decimals() {}

    /** Décimal positif ou nul, 9 décimales au plus, sans exposant ni signe. */
    public static final Pattern DECIMAL = Pattern.compile("(0|[1-9][0-9]{0,14})(\\.[0-9]{1,9})?");
    /** Devise ISO 4217 : trois lettres majuscules. */
    public static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Décimales des devises qui n'en ont pas deux (ISO 4217) ; toutes les autres en ont deux. */
    private static final Map<String, Integer> MINOR = Map.ofEntries(Map.entry("JPY", 0), Map.entry("KRW", 0), Map.entry("CLP", 0), Map.entry("ISK", 0), Map.entry("VND", 0),
            Map.entry("BHD", 3), Map.entry("KWD", 3), Map.entry("OMR", 3), Map.entry("JOD", 3), Map.entry("TND", 3), Map.entry("IQD", 3), Map.entry("LYD", 3));
    /** Précision intermédiaire des quantités converties (décimales, demi supérieur). */
    public static final int QUANTITY_SCALE = 9;

    public static boolean valid(String s) { return s != null && DECIMAL.matcher(s).matches(); }

    /** Pourcentage de 0 à 100. */
    public static boolean validPct(String s) { return valid(s) && new BigDecimal(s).compareTo(BigDecimal.valueOf(100)) <= 0; }

    public static BigDecimal of(String s) { return s == null ? BigDecimal.ZERO : new BigDecimal(s); }

    /** Décimales de la devise. */
    public static int minor(String currency) { return MINOR.getOrDefault(currency, 2); }

    /** Arrondi monétaire : à l'unité mineure de la devise, demi supérieur. */
    public static BigDecimal money(BigDecimal v, String currency) { return v.setScale(minor(currency), RoundingMode.HALF_UP); }

    /** Arrondi d'une quantité intermédiaire : {@value #QUANTITY_SCALE} décimales, demi supérieur. */
    public static BigDecimal quantity(BigDecimal v) { return v.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP); }

    /** Forme affichée : sans zéro inutile après la virgule, jamais d'exposant. */
    public static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString();
    }

    /** {@code 1 − pct/100}. */
    public static BigDecimal keep(String pct) {
        return pct == null ? BigDecimal.ONE : BigDecimal.ONE.subtract(new BigDecimal(pct).movePointLeft(2));
    }
}
