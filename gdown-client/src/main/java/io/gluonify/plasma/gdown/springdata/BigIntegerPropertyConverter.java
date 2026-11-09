package io.gluonify.plasma.gdown.springdata;

import io.gluonify.plasma.gdown.WideNumbers;
import java.math.BigInteger;
import org.neo4j.driver.Value;
import org.springframework.data.neo4j.core.convert.Neo4jPersistentPropertyConverter;

/**
 * Spring Data Neo4j converter of a {@link BigInteger} property: written as a Bolt integer when it fits 64 bits, else as its decimal text; read from an integer, a
 * float with no fraction or the text gDown sends for a wide integer.
 *
 * <pre>{@code @ConvertWith(converter = BigIntegerPropertyConverter.class) private BigInteger amount;}</pre>
 *
 * @author David CABILLIC
 */
public final class BigIntegerPropertyConverter implements Neo4jPersistentPropertyConverter<BigInteger> {

    @Override
    public Value write(final BigInteger source) {

        return WideNumbers.integer(source);
    }

    @Override
    public BigInteger read(final Value source) {

        return WideNumbers.integer(source);
    }
}
