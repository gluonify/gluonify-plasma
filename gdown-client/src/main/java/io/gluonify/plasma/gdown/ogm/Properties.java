package io.gluonify.plasma.gdown.ogm;

import java.math.BigDecimal;
import java.math.BigInteger;

/**
 * The numbers a graph property holds, as Neo4j-OGM hands them (a {@link Long}, a {@link Double} or the text gDown sends for a wide number).
 *
 * @author David CABILLIC
 */
final class Properties {

    private static final String THE_PROPERTY_VALUE = "The property value ";

    private Properties() {
    }

    /**
     * The integer of a property.
     *
     * @param value The property value, {@code null} for {@code null}
     * @return The integer
     * @throws IllegalArgumentException When the value is no integer
     */
    static BigInteger integer(final Object value) {

        if (value == null) {
            return null;
        }
        try {
            return decimal(value).toBigIntegerExact();
        } catch (final ArithmeticException e) {
            throw new IllegalArgumentException(THE_PROPERTY_VALUE + value + " has a fraction: it is no integer", e);
        }
    }

    /**
     * The decimal of a property.
     *
     * @param value The property value, {@code null} for {@code null}
     * @return The decimal
     * @throws IllegalArgumentException When the value is no finite number
     */
    static BigDecimal decimal(final Object value) {

        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal d) {
            return d;
        }
        if (value instanceof BigInteger i) {
            return new BigDecimal(i);
        }
        if (value instanceof Double d) {
            if (d.isNaN() || d.isInfinite()) {
                throw new IllegalArgumentException(THE_PROPERTY_VALUE + d + " is no finite number");
            }
            return BigDecimal.valueOf(d);
        }
        if (value instanceof Number n) {
            return BigDecimal.valueOf(n.longValue());
        }
        if (value instanceof CharSequence s) {
            try {
                return new BigDecimal(s.toString().strip());
            } catch (final NumberFormatException e) {
                throw new IllegalArgumentException(THE_PROPERTY_VALUE + "\"" + s + "\" is no number", e);
            }
        }
        throw new IllegalArgumentException(THE_PROPERTY_VALUE + value + " (" + value.getClass().getSimpleName() + ") is no number");
    }
}
