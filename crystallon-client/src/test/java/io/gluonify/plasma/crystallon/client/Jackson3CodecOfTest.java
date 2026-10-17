package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link Jackson3Codec#of} keeps the application's mapper as is. Runs only if Jackson 3 is present. */
class Jackson3CodecOfTest {
    @Test
    void usesTheMapperOfTheApplication() {
        assumeTrue(JacksonClasspath.jackson3());
        Holder.check();
    }

    /** Isolated: the test references Jackson 3 only in this class. */
    private static final class Holder {
        static void check() {
            final tools.jackson.databind.ObjectMapper mapper = tools.jackson.databind.json.JsonMapper.builder()
                    .enable(tools.jackson.databind.SerializationFeature.INDENT_OUTPUT).build();
            final String json = new String(Jackson3Codec.of(mapper).write(Map.of("a", 1)), StandardCharsets.UTF_8);
            assertEquals("{\n  \"a\" : 1\n}", json.replace("\r\n", "\n"));
        }
    }
}
