package io.gluonify.plasma.gdown;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import org.junit.jupiter.api.Test;
import org.neo4j.driver.Values;

/** Wide numbers over Bolt: parameters as text or Bolt numbers, results read from every Bolt form, the CAST expression. */
class WideNumbersTest {

    private static final BigInteger WIDE = BigInteger.TWO.pow(100);

    @Test
    void anIntegerParameterIsABoltIntegerWhenItFitsElseItsText() {

        assertEquals(Values.value(42L), WideNumbers.integer(BigInteger.valueOf(42)));
        assertEquals(Values.value(Long.MIN_VALUE), WideNumbers.integer(BigInteger.valueOf(Long.MIN_VALUE)));
        assertEquals(Values.value(WIDE.toString()), WideNumbers.integer(WIDE));
        assertEquals(Values.value(BigInteger.TWO.pow(63).toString()), WideNumbers.integer(BigInteger.TWO.pow(63)), "2^63 does not fit a long");
        assertEquals(Values.NULL, WideNumbers.integer((BigInteger) null));
    }

    @Test
    void aDecimalParameterIsItsPlainText() {

        assertEquals(Values.value("0.1"), WideNumbers.decimal(new BigDecimal("0.1")));
        assertEquals(Values.value("100000000000000000000"), WideNumbers.decimal(new BigDecimal("1E+20")));
        assertEquals(Values.NULL, WideNumbers.decimal((BigDecimal) null));
    }

    @Test
    void anIntegerIsReadFromEveryBoltForm() {

        assertEquals(BigInteger.valueOf(7), WideNumbers.integer(Values.value(7L)));
        assertEquals(WIDE, WideNumbers.integer(Values.value(WIDE.toString())), "the text gDown sends for a wide integer");
        assertEquals(WIDE, WideNumbers.integer(Values.value(" " + WIDE + " ")));
        assertEquals(BigInteger.valueOf(3), WideNumbers.integer(Values.value(3.0)), "a float with no fraction");
        assertNull(WideNumbers.integer(Values.NULL));
        assertNull(WideNumbers.integer((org.neo4j.driver.Value) null));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.integer(Values.value(2.5)));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.integer(Values.value("1.5")));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.integer(Values.value("x")));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> WideNumbers.integer(Values.value(true)));
        assertTrue(e.getMessage().contains("BOOLEAN"), e.getMessage());
    }

    @Test
    void aDecimalIsReadFromEveryBoltForm() {

        assertEquals(BigDecimal.valueOf(7), WideNumbers.decimal(Values.value(7L)));
        assertEquals(new BigDecimal("0.5"), WideNumbers.decimal(Values.value(0.5)));
        assertEquals(new BigDecimal("0.1"), WideNumbers.decimal(Values.value("0.1")), "the text gDown sends for a FLOAT128");
        assertNull(WideNumbers.decimal(Values.NULL));
        assertNull(WideNumbers.decimal((org.neo4j.driver.Value) null));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.decimal(Values.value(Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.decimal(Values.value(Double.POSITIVE_INFINITY)));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.decimal(Values.value("NaN")));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.decimal(Values.value(java.util.List.of(1))));
    }

    @Test
    void theCastExpressionNamesAWideType() {

        assertEquals("CAST($amount AS INT128)", WideNumbers.cast("amount", "int128"));
        assertEquals("CAST($m AS FLOAT256)", WideNumbers.cast("m", "FLOAT256"));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.cast("amount", "DECIMAL"));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.cast("amount", null));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.cast("1x", "INT128"));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.cast(null, "INT128"));
        assertThrows(IllegalArgumentException.class, () -> WideNumbers.cast("a b", "INT128"));
    }
}
