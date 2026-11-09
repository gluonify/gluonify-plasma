package io.gluonify.plasma.gdown.springdata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;

/** The Spring Data Neo4j converters: the same rules as {@code WideNumbers}, through the converter interface. */
class PropertyConvertersTest {

    private static final BigInteger WIDE = BigInteger.TWO.pow(100);

    @Test
    void bigIntegersRoundTrip() {

        final BigIntegerPropertyConverter c = new BigIntegerPropertyConverter();
        assertEquals(Values.value(42L), c.write(BigInteger.valueOf(42)));
        assertEquals(Values.value(WIDE.toString()), c.write(WIDE));
        assertEquals(WIDE, c.read(Values.value(WIDE.toString())));
        assertEquals(BigInteger.valueOf(42), c.read(Values.value(42L)));
        assertNull(c.read(Values.NULL));
        assertEquals(Values.NULL, c.write(null));
    }

    @Test
    void bigDecimalsRoundTrip() {

        final BigDecimalPropertyConverter c = new BigDecimalPropertyConverter();
        assertEquals(Values.value("0.1"), c.write(new BigDecimal("0.1")));
        assertEquals(new BigDecimal("0.1"), c.read(Values.value("0.1")));
        assertEquals(new BigDecimal("0.5"), c.read(Values.value(0.5)));
        assertNull(c.read(Values.NULL));
        assertEquals(Values.NULL, c.write(null));
    }
}
