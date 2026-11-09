package io.gluonify.plasma.gdown.springdata;

import io.gluonify.plasma.gdown.WideNumbers;
import java.math.BigDecimal;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.convert.Neo4jPersistentPropertyConverter;

/**
 * Spring Data Neo4j converter of a {@link BigDecimal} property: written as its plain decimal text; read from an integer, a float or the text gDown sends for a
 * {@code FLOAT128} or {@code FLOAT256}.
 *
 * <pre>{@code @ConvertWith(converter = BigDecimalPropertyConverter.class) private BigDecimal mass;}</pre>
 *
 * @author David CABILLIC
 */
public final class BigDecimalPropertyConverter implements Neo4jPersistentPropertyConverter<BigDecimal> {

    @Override
    public Value write(final BigDecimal source) {

        return WideNumbers.decimal(source);
    }

    @Override
    public BigDecimal read(final Value source) {

        return WideNumbers.decimal(source);
    }
}
