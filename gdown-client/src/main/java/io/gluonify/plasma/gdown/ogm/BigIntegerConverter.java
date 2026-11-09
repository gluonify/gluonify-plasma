package io.gluonify.plasma.gdown.ogm;

import java.math.BigInteger;
import org.neo4j.ogm.typeconversion.AttributeConverter;

/**
 * Neo4j-OGM converter of a {@link BigInteger} attribute: stored as a Bolt integer when it fits 64 bits, else as its decimal text (gDown stores the text as a
 * string: use {@code CAST} in a statement to store an {@code INT128} proper); read back from an integer, a float with no fraction or a text.
 *
 * <pre>{@code @Convert(BigIntegerConverter.class) private BigInteger amount;}</pre>
 *
 * @author David CABILLIC
 */
public final class BigIntegerConverter implements AttributeConverter<BigInteger, Object> {

    @Override
    public Object toGraphProperty(final BigInteger value) {

        if (value == null) {
            return null;
        }
        return value.bitLength() < Long.SIZE ? (Object) value.longValueExact() : value.toString();
    }

    @Override
    public BigInteger toEntityAttribute(final Object value) {

        return Properties.integer(value);
    }
}
