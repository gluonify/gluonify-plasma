package io.gluonify.plasma.services.internal;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The module's own JSON reader and writer: every escape, number shape, structure and refusal. */
class JsonGapsTest {

    static IllegalArgumentException bad(final String text) {
        return assertThrows(IllegalArgumentException.class, () -> Json.parse(text), text);
    }

    @Test
    void literalsAndWhitespace() {
        assertEquals(Boolean.TRUE, Json.parse(" \t\r\n true \n"));
        assertEquals(Boolean.FALSE, Json.parse("false"));
        assertNull(Json.parse("null"));
    }

    @Test
    void numbersOfEveryShape() {
        assertEquals(42L, Json.parse("42"));
        assertEquals(-7L, Json.parse("-7"));
        assertEquals(1.5, Json.parse("1.5"));
        assertEquals(-2.5e3, Json.parse("-2.5e3"));
        assertEquals(1e20, Json.parse("99999999999999999999"), "too large for a long: a double");
        assertEquals(List.of(1L, 2L), Json.parse("[1,2]"));
    }

    @Test
    void malformedNumbersAreRefused() {
        assertTrue(bad("-").getMessage().contains("bad number"));
        assertTrue(bad("1-2").getMessage().contains("bad number"));
        assertTrue(bad("1.2.3").getMessage().contains("bad number"));
    }

    @Test
    void stringsWithEveryEscape() {
        assertEquals("\"\\/\b\f\n\r\t", Json.parse("\"\\\"\\\\\\/\\b\\f\\n\\r\\t\""));
        assertEquals("A\u00e9", Json.parse("\"\\u0041\\u00E9\""));
        assertEquals("", Json.parse("\"\""));
    }

    @Test
    void malformedStringsAreRefused() {
        assertTrue(bad("\"a\\x\"").getMessage().contains("bad escape"));
        assertTrue(bad("\"\\u12").getMessage().contains("bad unicode escape"));
        assertTrue(bad("\"\\u12zz\"").getMessage().contains("bad unicode escape"));
        assertTrue(bad("\"a\nb\"").getMessage().contains("control character"));
        assertTrue(bad("\"unterminated").getMessage().contains("unexpected end"));
    }

    @Test
    void objectsAndArrays() {
        assertEquals(Map.of(), Json.parse("{}"));
        assertEquals(Map.of(), Json.parse("{ }"));
        assertEquals(List.of(), Json.parse("[]"));
        assertEquals(List.of(), Json.parse("[ ]"));
        final Map<String, Object> o = Json.object("{\"a\": [1, {\"b\": null}], \"c\" : \"d\"}");
        assertEquals(List.of("a", "c"), new ArrayList<>(o.keySet()), "insertion order is kept");
        assertEquals("d", o.get("c"));
    }

    @Test
    void structuralErrorsAreRefused() {
        assertTrue(bad("{1:2}").getMessage().contains("a key is expected"));
        assertTrue(bad("{\"a\" 1}").getMessage().contains("':' is expected"));
        assertTrue(bad("{\"a\":1 \"b\":2}").getMessage().contains("',' or '}' is expected"));
        assertTrue(bad("[1 2]").getMessage().contains("',' or ']' is expected"));
        assertTrue(bad("[1,").getMessage().contains("unexpected end"));
        assertTrue(bad("").getMessage().contains("unexpected end"));
        assertTrue(bad("@").getMessage().contains("unexpected character"));
        assertTrue(bad("tru").getMessage().contains("unexpected literal"));
        assertTrue(bad("nul").getMessage().contains("unexpected literal"));
        assertTrue(bad("1 2").getMessage().contains("trailing characters"));
    }

    @Test
    void aDocumentNestedTooDeeplyIsRefused() {
        assertTrue(bad("[".repeat(500) + "]".repeat(500)).getMessage().contains("too deeply nested"));
        assertEquals(List.of(), ((List<?>) ((List<?>) Json.parse("[[]]")).get(0)));
    }

    @Test
    void objectRequiresAnObject() {
        assertThrows(IllegalArgumentException.class, () -> Json.object("[1]"));
        assertThrows(IllegalArgumentException.class, () -> Json.object("\"x\""));
    }

    @Test
    void accessorsTolerateMissingAndWrongTypedValues() {
        final Map<String, Object> m = Json.object("{\"s\":\"  v \",\"blank\":\"  \",\"n\":7,\"f\":2.9,\"o\":{\"k\":1},\"l\":[\"a\",1,\"b\"],\"one\":\"x\",\"t\":true}");
        assertEquals("v", Json.str(m, "s"));
        assertNull(Json.str(m, "blank"));
        assertNull(Json.str(m, "n"));
        assertNull(Json.str(m, "absent"));
        assertNull(Json.str(null, "s"));
        assertEquals(7, Json.num(m, "n", -1));
        assertEquals(2, Json.num(m, "f", -1));
        assertEquals(-1, Json.num(m, "s", -1));
        assertEquals(-1, Json.num(null, "n", -1));
        assertEquals(Map.of("k", 1L), Json.obj(m, "o"));
        assertEquals(Map.of(), Json.obj(m, "s"));
        assertEquals(Map.of(), Json.obj(null, "o"));
        assertEquals(List.of("a", "b"), Json.strings(m, "l"));
        assertEquals(List.of("x"), Json.strings(m, "one"));
        assertEquals(List.of(), Json.strings(m, "t"));
        assertEquals(List.of(), Json.strings(null, "l"));
    }

    @Test
    void writingEveryKindOfValue() {
        assertEquals("null", Json.write(null));
        assertEquals("true", Json.write(true));
        assertEquals("12", Json.write(12));
        assertEquals("1.5", Json.write(1.5));
        assertEquals("{}", Json.write(Map.of()));
        assertEquals("[]", Json.write(List.of()));
        assertEquals("[1, \"a\", null]", Json.write(java.util.Arrays.asList(1, "a", null)));
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("a", 1);
        m.put("b", Map.of("c", List.of(true)));
        assertEquals("{\n  \"a\": 1,\n  \"b\": {\n    \"c\": [true]\n  }\n}", Json.write(m));
    }

    @Test
    void writingEscapesWhatMustBeEscaped() {
        assertEquals("\"\\\"\\\\\\n\\r\\t\\u0001\\u001f\"", Json.write("\"\\\n\r\t\u0001\u001f"));
        assertEquals("\"\u00e9/ \"", Json.write("\u00e9/ "), "other characters are left as they are");
        assertEquals("{\n  \"7\": 1\n}", Json.write(Map.of(7, 1)), "a key that is not a string is written as one");
    }

    @Test
    void writingAnUnknownTypeIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> Json.write(new Object()));
        assertThrows(IllegalArgumentException.class, () -> Json.write(List.of(new StringBuilder("x"))));
    }

    @Test
    void whatIsWrittenIsReadBack() {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "line\nquote\" back\\ tab\t \u0007");
        m.put("list", List.of(1L, "two", Map.of("k", false)));
        m.put("nothing", null);
        assertEquals(m, Json.object(Json.write(m)));
    }
}
