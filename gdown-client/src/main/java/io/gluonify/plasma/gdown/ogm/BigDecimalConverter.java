package io.gluonify.plasma.gdown.ogm;

import java.math.BigDecimal;
import org.neo4j.ogm.typeconversion.AttributeConverter;

/**
 * Neo4j-OGM converter of a {@link BigDecimal} attribute: stored as its plain decimal text (gDown stores the text as a string: use {@code CAST} in a statement to
 * store a {@code FLOAT128} proper); read back from an integer, a float or a text, the text gDown sends for a {@code FLOAT128} or {@code FLOAT256} included.
 *
 * <pre>{@code @Convert(BigDecimalConverter.class) private BigDecimal mass;}</pre>
 *
 * @author David CABILLIC
 */
public final class BigDecimalConverter implements AttributeConverter<BigDecimal, Object> {

    @Override
    public Object toGraphProperty(final BigDecimal value) {

        return value == null ? null : value.toPlainString();
    }

    @Override
    public BigDecimal toEntityAttribute(final Object value) {

        return Properties.decimal(value);
    }
}
