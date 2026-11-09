package io.gluonify.plasma.gdown.ogm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The Neo4j-OGM converters: a value that fits a Bolt integer is stored as one, a wider one as text; reading accepts every form OGM hands over. */
class OgmConvertersTest {

    private static final BigInteger WIDE = BigInteger.TWO.pow(100);
    private final BigIntegerConverter integers = new BigIntegerConverter();
    private final BigDecimalConverter decimals = new BigDecimalConverter();

    @Test
    void aBigIntegerIsStoredAsALongWhenItFitsElseAsText() {

        assertEquals(42L, integers.toGraphProperty(BigInteger.valueOf(42)));
        assertEquals(WIDE.toString(), integers.toGraphProperty(WIDE));
        assertNull(integers.toGraphProperty(null));
    }

    @Test
    void aBigIntegerIsReadFromEveryPropertyForm() {

        assertEquals(BigInteger.valueOf(42), integers.toEntityAttribute(42L));
        assertEquals(BigInteger.valueOf(42), integers.toEntityAttribute(42));
        assertEquals(BigInteger.valueOf(3), integers.toEntityAttribute(3.0));
        assertEquals(WIDE, integers.toEntityAttribute(WIDE.toString()));
        assertEquals(WIDE, integers.toEntityAttribute(new StringBuilder(WIDE.toString())));
        assertEquals(WIDE, integers.toEntityAttribute(WIDE));
        assertEquals(WIDE, integers.toEntityAttribute(new BigDecimal(WIDE)));
        assertNull(integers.toEntityAttribute(null));
        assertThrows(IllegalArgumentException.class, () -> integers.toEntityAttribute(2.5));
        assertThrows(IllegalArgumentException.class, () -> integers.toEntityAttribute("x"));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> integers.toEntityAttribute(List.of(1)));
        assertTrue(e.getMessage().contains("is no number"), e.getMessage());
    }

    @Test
    void aBigDecimalIsStoredAsItsPlainText() {

        assertEquals("0.1", decimals.toGraphProperty(new BigDecimal("0.1")));
        assertEquals("100000000000000000000", decimals.toGraphProperty(new BigDecimal("1E+20")));
        assertNull(decimals.toGraphProperty(null));
    }

    @Test
    void aBigDecimalIsReadFromEveryPropertyForm() {

        assertEquals(new BigDecimal("0.1"), decimals.toEntityAttribute("0.1"));
        assertEquals(new BigDecimal("0.5"), decimals.toEntityAttribute(0.5));
        assertEquals(BigDecimal.valueOf(7), decimals.toEntityAttribute(7L));
        assertNull(decimals.toEntityAttribute(null));
        assertThrows(IllegalArgumentException.class, () -> decimals.toEntityAttribute(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> decimals.toEntityAttribute("NaN"));
    }
}
