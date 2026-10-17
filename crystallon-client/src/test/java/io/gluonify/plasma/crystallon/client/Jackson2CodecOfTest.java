package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link Jackson2Codec#of} keeps the application's mapper as is. Runs only if Jackson 2 is present. */
class Jackson2CodecOfTest {
    @Test
    void usesTheMapperOfTheApplication() {
        assumeTrue(JacksonClasspath.jackson2());
        Holder.check();
    }

    /** Isolated: the test references Jackson 2 only in this class. */
    private static final class Holder {
        static void check() {
            final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                    .enable(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT);
            final String json = new String(Jackson2Codec.of(mapper).write(Map.of("a", 1)), StandardCharsets.UTF_8);
            assertEquals("{\n  \"a\" : 1\n}", json.replace("\r\n", "\n"));
        }
    }
}
