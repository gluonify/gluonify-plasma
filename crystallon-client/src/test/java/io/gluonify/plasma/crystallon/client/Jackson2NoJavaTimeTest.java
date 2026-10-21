package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Jackson 2 without {@code jackson-datatype-jsr310} (execution {@code jackson2-without-jsr310}): the codec works, and rejects a date with the right advice. */
class Jackson2NoJavaTimeTest {
    @Test
    void theClasspathHasJackson2OnlyAndNoJavaTimeModule() {
        assertEquals("2-without-jsr310", JacksonClasspath.EXPECTED);
        assertTrue(JacksonClasspath.jackson2());
        assertFalse(JacksonClasspath.jackson3());
        assertFalse(JacksonClasspath.jsr310());
    }

    @Test
    void payloadsWithoutJavaTimeStillWork() {
        final PayloadCodec codec = PayloadCodec.detect();
        assertEquals("Jackson2Codec", codec.getClass().getSimpleName());
        assertEquals("{\"a\":\"b\",\"n\":1.50}", new String(codec.write(new java.util.TreeMap<>(Map.of("a", "b", "n", new java.math.BigDecimal("1.50")))), StandardCharsets.UTF_8));
    }

    @Test
    void aLocalDateIsRefusedWithTheArtifactToAdd() {
        final var input = Map.of("d", LocalDate.of(2026, 1, 1));
        final var subject = PayloadCodec.detect();
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> subject.write(input));
        assertTrue(e.getMessage().contains("jackson-datatype-jsr310"), e.getMessage());
    }
}
