package io.gluonify.plasma.contracts;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.regex.Pattern;

/** Decimal amounts as strings ({@code contracts.md} § 2.4): never a {@code double}. */
public final class Decimals {
    private Decimals() {}

    /** Positive or zero decimal, at most 9 decimal places, no exponent or sign. */
    public static final Pattern DECIMAL = Pattern.compile("(0|[1-9][0-9]{0,14})(\\.[0-9]{1,9})?");
    /** ISO 4217 currency: three uppercase letters. */
    public static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    /** Decimal places of currencies that do not have two (ISO 4217); all the others have two. */
    private static final Map<String, Integer> MINOR = Map.ofEntries(Map.entry("JPY", 0), Map.entry("KRW", 0), Map.entry("CLP", 0), Map.entry("ISK", 0), Map.entry("VND", 0),
            Map.entry("BHD", 3), Map.entry("KWD", 3), Map.entry("OMR", 3), Map.entry("JOD", 3), Map.entry("TND", 3), Map.entry("IQD", 3), Map.entry("LYD", 3));
    /** Intermediate precision of converted quantities (decimal places, half up). */
    public static final int QUANTITY_SCALE = 9;

    public static boolean valid(String s) { return s != null && DECIMAL.matcher(s).matches(); }

    /** Percentage from 0 to 100. */
    public static boolean validPct(String s) { return valid(s) && new BigDecimal(s).compareTo(BigDecimal.valueOf(100)) <= 0; }

    public static BigDecimal of(String s) { return s == null ? BigDecimal.ZERO : new BigDecimal(s); }

    /** Decimal places of the currency. */
    public static int minor(String currency) { return MINOR.getOrDefault(currency, 2); }

    /** Monetary rounding: to the minor unit of the currency, half up. */
    public static BigDecimal money(BigDecimal v, String currency) { return v.setScale(minor(currency), RoundingMode.HALF_UP); }

    /** Rounding of an intermediate quantity: {@value #QUANTITY_SCALE} decimal places, half up. */
    public static BigDecimal quantity(BigDecimal v) { return v.setScale(QUANTITY_SCALE, RoundingMode.HALF_UP); }

    /** Displayed form: no useless trailing zero after the decimal point, never an exponent. */
    public static String plain(BigDecimal v) {
        BigDecimal s = v.stripTrailingZeros();
        if (s.scale() < 0) s = s.setScale(0);
        return s.toPlainString();
    }

    /** {@code 1 - pct/100}. */
    public static BigDecimal keep(String pct) {
        return pct == null ? BigDecimal.ONE : BigDecimal.ONE.subtract(new BigDecimal(pct).movePointLeft(2));
    }
}
