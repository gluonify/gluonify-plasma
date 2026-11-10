package io.gluonify.plasma.gdown;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Locale;
import java.util.Set;
import org.neo4j.driver.Value;
import org.neo4j.driver.Values;

/**
 * gDown's numbers wider than Bolt's: an integer beyond 64 bits ({@code INT128}, {@code UINT64}…, GQL batch G9) and a {@code FLOAT128} or {@code FLOAT256} (batch G10)
 * travel over Bolt as their decimal text, since PackStream has no marker for them. A parameter is sent as text and cast in the statement
 * ({@code CAST($p AS INT128)}, {@link #cast}); a value read is text when it is wide, a Bolt integer or float when it fits one.
 *
 * @author David CABILLIC
 */
public final class WideNumbers {

    /** The GQL type names a text parameter may be cast to. */
    public static final Set<String> TYPES = Set.of("INT8", "INT16", "INT32", "INT64", "INT128", "INT256", "UINT8", "UINT16", "UINT32", "UINT64", "UINT128", "UINT256", "FLOAT128",
            "FLOAT256", "DECIMAL");

    /** A decimal type with its precision and scale: {@code DECIMAL(p)} or {@code DECIMAL(p, s)} ({@code DEC} as a synonym), checked by gDown (1 to 38 digits). */
    private static final java.util.regex.Pattern DECIMAL_TYPE = java.util.regex.Pattern.compile("(?:DECIMAL|DEC)(?:\\((\\d{1,2})(?:,\\s*(\\d{1,2}))?\\))?");

    private static final String THE_VALUE = "The value ";

    private WideNumbers() {
    }

    /**
     * An integer as a Bolt parameter: a Bolt integer when it fits 64 bits, else its decimal text, to cast in the statement.
     *
     * @param value The integer, {@code null} for a null value
     * @return The value
     */
    public static Value integer(final BigInteger value) {

        if (value == null) {
            return Values.NULL;
        }
        return value.bitLength() < Long.SIZE ? Values.value(value.longValueExact()) : Values.value(value.toString());
    }

    /**
     * A decimal as a Bolt parameter: its plain decimal text, to cast in the statement ({@code CAST($p AS FLOAT128)}).
     *
     * @param value The decimal, {@code null} for a null value
     * @return The value
     */
    public static Value decimal(final BigDecimal value) {

        return value == null ? Values.NULL : Values.value(value.toPlainString());
    }

    /**
     * The integer a Bolt value holds: a Bolt integer, the decimal text gDown sends for a wide one, or a float with no fraction.
     *
     * @param value The value, {@code null} or a null value for {@code null}
     * @return The integer
     * @throws IllegalArgumentException When the value is no integer
     */
    public static BigInteger integer(final Value value) {

        if (value == null || value.isNull()) {
            return null;
        }
        final Object o = value.asObject();
        if (o instanceof Long l) {
            return BigInteger.valueOf(l);
        }
        if (o instanceof Double d) {
            return exactInteger(BigDecimal.valueOf(d), value);
        }
        if (o instanceof String s) {
            return exactInteger(parse(s, value), value);
        }
        throw noNumber(value);
    }

    /**
     * The decimal a Bolt value holds: a Bolt integer, a float (as its shortest decimal text, {@link BigDecimal#valueOf(double)}) or the decimal text gDown sends for a wide float.
     *
     * @param value The value, {@code null} or a null value for {@code null}
     * @return The decimal
     * @throws IllegalArgumentException When the value is no number, or is NaN or infinite
     */
    public static BigDecimal decimal(final Value value) {

        if (value == null || value.isNull()) {
            return null;
        }
        final Object o = value.asObject();
        if (o instanceof Long l) {
            return BigDecimal.valueOf(l);
        }
        if (o instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException(THE_VALUE + d + " is no finite number: a BigDecimal cannot hold it");
            }
            return BigDecimal.valueOf(d);
        }
        if (o instanceof String s) {
            return parse(s, value);
        }
        throw noNumber(value);
    }

    /**
     * The Cypher text that casts a text parameter to a wide type: {@code CAST($p AS INT128)}, {@code CAST($p AS DECIMAL(10, 2))}.
     *
     * @param parameter The parameter name, without {@code $}
     * @param type      One of {@link #TYPES}, in any case; {@code DECIMAL} may carry a precision and a scale ({@code DECIMAL(10, 2)}, {@code DEC(10)})
     * @return The expression
     * @throws IllegalArgumentException For a type that is not one of {@link #TYPES} or a parameter name that is no identifier
     */
    public static String cast(final String parameter, final String type) {

        if (parameter == null || !parameter.matches("[A-Za-z_]\\w*+")) {
            throw new IllegalArgumentException("The parameter name `" + parameter + "` is no identifier");
        }
        final String upper = type == null ? "" : type.strip().toUpperCase(Locale.ROOT);
        final java.util.regex.Matcher decimal = DECIMAL_TYPE.matcher(upper);
        if (decimal.matches()) return "CAST($" + parameter + " AS " + decimalType(decimal) + ")";
        if (!TYPES.contains(upper)) {
            throw new IllegalArgumentException("The type " + type + " is none of " + TYPES);
        }
        return "CAST($" + parameter + " AS " + upper + ")";
    }

    /** {@code DECIMAL}, {@code DECIMAL(p)} or {@code DECIMAL(p, s)} as gDown writes the type. */
    private static String decimalType(final java.util.regex.Matcher m) {

        if (m.group(1) == null) return "DECIMAL";
        return "DECIMAL(" + m.group(1) + (m.group(2) == null ? "" : ", " + m.group(2)) + ")";
    }

    private static BigDecimal parse(final String text, final Value value) {

        try {
            return new BigDecimal(text.strip());
        } catch (final NumberFormatException e) {
            throw noNumber(value);
        }
    }

    private static BigInteger exactInteger(final BigDecimal d, final Value value) {

        try {
            return d.toBigIntegerExact();
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException(THE_VALUE + value + " has a fraction: it is no integer", e);
        }
    }

    private static IllegalArgumentException noNumber(final Value value) {

        return new IllegalArgumentException(THE_VALUE + value + " (" + value.type().name() + ") is no number");
    }
}
