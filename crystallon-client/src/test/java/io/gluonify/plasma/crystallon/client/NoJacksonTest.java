package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.crystallon.Check;
import io.gluonify.plasma.crystallon.ErrorBody;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** With no Jackson at all on the classpath (execution {@code no-jackson}): clear error, and the client works with a supplied codec. */
class NoJacksonTest {
    @Test
    void theClasspathHasNoJackson() {
        assertEquals("none", JacksonClasspath.EXPECTED);
        assertFalse(JacksonClasspath.jackson3());
        assertFalse(JacksonClasspath.jackson2());
    }

    @Test
    void detectExplainsWhatToAdd() {
        final IllegalStateException e = assertThrows(IllegalStateException.class, PayloadCodec::detect);
        assertTrue(e.getMessage().contains("tools.jackson.core:jackson-databind"), e.getMessage());
        assertTrue(e.getMessage().contains("com.fasterxml.jackson.core:jackson-databind"), e.getMessage());
        assertTrue(e.getMessage().contains("PayloadCodec"), e.getMessage());
    }

    @Test
    void buildingWithoutACodecFailsEarlyWithTheSameExplanation() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> CrystallonClient.builder().baseUrl("http://crystallon").build());
        assertTrue(e.getMessage().contains("No JSON library"), e.getMessage());
    }

    @Test
    void aHandWrittenCodecIsEnoughAndNoJacksonClassIsTouched() {
        final PayloadCodec byHand = new PayloadCodec() {
            @Override
            public byte[] write(final Object value) {
                return "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T read(final byte[] json, final Class<T> type) {
                if (type == Check.class) {
                    return (T) new Check(true, 4, null, null, null);
                }
                return (T) new ErrorBody("CRYSTALLON-0004", "Template not found", null);
            }
        };
        try (FakeCrystallon fake = new FakeCrystallon()) {
            final CrystallonClient client = fake.client().json(byHand).build();

            assertEquals(2, client.byId("t1").render(Map.of()).pages());
            assertEquals("{\"data\":{}}", fake.last().text());

            fake.reply(FakeCrystallon.Reply.json(200, "{}"));
            assertEquals(4, client.byId("t1").validate(Map.of()).pages());

            fake.reply(FakeCrystallon.Reply.json(404, "{}"));
            assertEquals("CRYSTALLON-0004", assertThrows(CrystallonException.class, () -> client.byId("t1").render(Map.of())).code());
        }
    }
}
