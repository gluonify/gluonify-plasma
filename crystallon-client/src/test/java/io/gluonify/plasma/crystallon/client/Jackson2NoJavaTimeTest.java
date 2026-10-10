package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Jackson 2 sans {@code jackson-datatype-jsr310} (exécution {@code jackson2-without-jsr310}) : le codec marche, et refuse une date avec le bon conseil. */
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
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> PayloadCodec.detect().write(Map.of("d", LocalDate.of(2026, 1, 1))));
        assertTrue(e.getMessage().contains("jackson-datatype-jsr310"), e.getMessage());
    }
}
