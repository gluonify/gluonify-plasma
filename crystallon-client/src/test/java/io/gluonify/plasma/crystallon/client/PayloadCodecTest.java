package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.crystallon.Check;
import io.gluonify.plasma.crystallon.ErrorBody;
import io.gluonify.plasma.crystallon.RenderParams;
import io.gluonify.plasma.crystallon.RenderRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * La passerelle JSON, avec le Jackson du chemin de classes de l'exécution surefire en cours (Jackson 2 et 3, 3 seul, 2 seul) : le même payload doit
 * donner le même JSON dans les trois cas.
 */
class PayloadCodecTest {
    private final PayloadCodec codec = PayloadCodec.detect();

    private String json(final Object value) {
        return new String(codec.write(value), StandardCharsets.UTF_8);
    }

    @Test
    void theClasspathHasExactlyTheJacksonTheExecutionDeclares() {
        assertEquals(!JacksonClasspath.EXPECTED.equals("2"), JacksonClasspath.jackson3(), "Jackson 3 on the classpath");
        assertEquals(!JacksonClasspath.EXPECTED.equals("3"), JacksonClasspath.jackson2(), "Jackson 2 on the classpath");
        assertEquals(!JacksonClasspath.EXPECTED.equals("3"), JacksonClasspath.jsr310(), "jsr310 on the classpath");
    }

    @Test
    void detectPrefersJackson3ThenJackson2() {
        final String expected = JacksonClasspath.EXPECTED.equals("2") ? "Jackson2Codec" : "Jackson3Codec";
        assertEquals(expected, codec.getClass().getSimpleName());
    }

    @Test
    void writesDatesAsIsoStringsNeverAsTimestamps() {
        final String json = json(TestPayloads.invoice());
        assertTrue(json.contains("\"date\":\"2026-10-10\""), json);
        assertTrue(json.contains("\"issuedAt\":\"2026-10-10T08:30:00Z\""), json);
        assertFalse(json.contains("[2026"), json);
    }

    @Test
    void writesBigDecimalsAsPlainNumbersWithTheirScale() {
        final String json = json(TestPayloads.invoice());
        assertTrue(json.contains("\"total\":1234.50"), json);
        assertTrue(json.contains("\"price\":874.50"), json);
        assertEquals("{\"n\":1000}", json(Map.of("n", new BigDecimal("1E+3"))));
        assertEquals("{\"n\":0.00001}", json(Map.of("n", new BigDecimal("0.00001"))));
    }

    @Test
    void writesListsNestedRecordsAndRenamedProperties() {
        final String json = json(TestPayloads.invoice());
        assertTrue(json.contains("\"invoice-number\":\"2026-001\""), json);
        assertTrue(json.contains("\"items\":[{"), json);
        assertTrue(json.contains("\"label\":\"Hours\""), json);
        assertTrue(json.contains("\"label\":\"Travel\""), json);
        assertFalse(json.contains("\"note\""), "NON_NULL");
        assertFalse(json.contains("invoiceNumber"), json);
    }

    @Test
    void writesALocalDateAlone() {
        assertEquals("{\"d\":\"2026-02-28\"}", json(Map.of("d", LocalDate.of(2026, 2, 28))));
        assertEquals("{\"t\":\"PT1H30M\"}", json(Map.of("t", Duration.ofMinutes(90))));
    }

    @Test
    void theRenderRequestHasDataAndOnlyTheSetParams() {
        assertEquals("{\"data\":{},\"params\":{\"lang\":\"fr\",\"currency\":\"CHF\"}}",
                json(new RenderRequest(null, new RenderParams("fr", null, "CHF", null, null, null))).replace(" ", ""));
        assertEquals("{\"data\":{},\"params\":{}}", json(new RenderRequest(Map.of(), new RenderParams(null, null, null, null, null, null))));
        assertEquals("{\"data\":{}}", json(new RenderRequest(null, null)));
    }

    @Test
    void readsACheckFromTheServerAnswerAndIgnoresUnknownProperties() {
        final Check check = codec.read(("{\"valid\":true,\"pages\":3,\"warnings\":[\"w\"],\"errors\":[],"
                + "\"warningDetails\":[{\"code\":null,\"message\":\"w\",\"path\":\"body[0]\"}],\"future\":{\"a\":1}}").getBytes(StandardCharsets.UTF_8), Check.class);
        assertTrue(check.valid());
        assertEquals(3, check.pages());
        assertEquals(List.of("w"), check.warnings());
        assertEquals("body[0]", check.warningDetails().get(0).path());
    }

    @Test
    void aCheckWithoutListsHasEmptyLists() {
        final Check check = codec.read("{\"valid\":false,\"pages\":0}".getBytes(StandardCharsets.UTF_8), Check.class);
        assertEquals(List.of(), check.warnings());
        assertEquals(List.of(), check.errors());
        assertEquals(List.of(), check.warningDetails());
    }

    @Test
    void readsAnErrorBodyWithAndWithoutPath() {
        final ErrorBody withPath = codec.read("{\"code\":\"CRYSTALLON-0014\",\"message\":\"m\",\"path\":\"header[0]\"}".getBytes(StandardCharsets.UTF_8), ErrorBody.class);
        assertEquals(new ErrorBody("CRYSTALLON-0014", "m", "header[0]"), withPath);
        final ErrorBody withoutPath = codec.read("{\"code\":\"CRYSTALLON-0004\",\"message\":\"m\"}".getBytes(StandardCharsets.UTF_8), ErrorBody.class);
        assertNull(withoutPath.path());
    }

    /** Un bean dont l'accesseur échoue : ni Jackson 2 ni Jackson 3 ne peuvent l'écrire. */
    public static final class Broken {
        public String getValue() {
            throw new IllegalStateException("boom");
        }
    }

    @Test
    void aPayloadThatCannotBeWrittenIsAnIllegalArgument() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> codec.write(new Broken()));
        assertTrue(e.getMessage().startsWith("cannot write the Crystallon payload"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> codec.read("{".getBytes(StandardCharsets.UTF_8), Check.class));
    }
}
