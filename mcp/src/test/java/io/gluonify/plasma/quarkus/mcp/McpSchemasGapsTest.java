package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Type;
import java.math.BigInteger;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** The input schema derived from Java types: every scalar, generic and unusual shape an arguments record can hold. */
class McpSchemasGapsTest {
    static final ObjectMapper M = new ObjectMapper();

    enum Level {
        LOW, HIGH
    }

    static class Bean {
        public String name;
    }

    record Described(@McpParam("The customer's name") String name, Optional<String> note, OptionalLong count, OptionalDouble ratio) {
    }

    record Scalars(short s, byte b, Short boxedShort, Byte boxedByte, Long boxedLong, Integer boxedInt, BigInteger big, float f, Float boxedFloat, Double boxedDouble, Boolean boxedBoolean, Character boxedChar) {
    }

    @SuppressWarnings({"rawtypes", "unused"})
    static class Holder<T> {
        List<? extends Level> upper;
        List<? extends Number> numbers;
        List<?> unbounded;
        List<String>[] genericArray;
        Optional<Integer> optionalInt;
        Optional<List<String>> optionalList;
        Map<String, List<Level>> nested;
        Collection rawCollection;
        Map rawMap;
        Optional rawOptional;
        Set<Level> levels;
        Level level;
        Bean bean;
        JsonNode node;
        Object any;
        T variable;
        Map.Entry<String, Integer> entry;
        Map<String, ?> wildcardValues;
    }

    static Type type(final String field) throws NoSuchFieldException {
        return Holder.class.getDeclaredField(field).getGenericType();
    }

    static JsonNode json(final String text) {
        return M.readTree(text);
    }

    @Test
    void wildcardsFollowTheirUpperBound() throws Exception {
        assertEquals(json("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}}"), McpSchemas.of(type("upper")));
        assertEquals(json("{\"type\":\"array\",\"items\":{}}"), McpSchemas.of(type("unbounded")));
        assertEquals(json("{\"type\":\"object\",\"additionalProperties\":{}}"), McpSchemas.of(type("wildcardValues")));
    }

    @Disabled("DEFECT: a wildcard bounded by Number (List<? extends Number>) derives {\"type\":\"object\"} for its items, because Number is neither a scalar class of"
            + " McpSchemas.scalarType nor a record: the JSON type of a Number is \"number\"")
    @Test
    void aNumberBoundIsANumber() throws Exception {
        assertEquals(json("{\"type\":\"array\",\"items\":{\"type\":\"number\"}}"), McpSchemas.of(type("numbers")));
    }

    @Test
    void genericArraysCollectionsMapsAndOptionals() throws Exception {
        assertEquals(json("{\"type\":\"array\",\"items\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}}"), McpSchemas.of(type("genericArray")));
        assertEquals(json("{\"type\":\"integer\"}"), McpSchemas.of(type("optionalInt")));
        assertEquals(json("{\"type\":\"array\",\"items\":{\"type\":\"string\"}}"), McpSchemas.of(type("optionalList")));
        assertEquals(json("{\"type\":\"object\",\"additionalProperties\":{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}}}"), McpSchemas.of(type("nested")));
        assertEquals(json("{\"type\":\"array\",\"items\":{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]},\"uniqueItems\":true}"), McpSchemas.of(type("levels")));
    }

    @Test
    void rawAndPlainClasses() throws Exception {
        assertEquals(json("{\"type\":\"array\"}"), McpSchemas.of(type("rawCollection")));
        assertEquals(json("{\"type\":\"object\"}"), McpSchemas.of(type("rawMap")));
        assertEquals(json("{\"type\":\"object\"}"), McpSchemas.of(type("rawOptional")));
        assertEquals(json("{\"type\":\"string\",\"enum\":[\"LOW\",\"HIGH\"]}"), McpSchemas.of(type("level")));
        assertEquals(json("{\"type\":\"object\"}"), McpSchemas.of(type("bean")), "a bean is an object whose fields are not described");
        assertEquals(json("{}"), McpSchemas.of(type("node")), "a JsonNode is anything");
        assertEquals(json("{}"), McpSchemas.of(type("any")));
        assertEquals(json("{}"), McpSchemas.of(type("variable")), "a type variable is not known: anything");
        assertEquals(json("{\"type\":\"object\"}"), McpSchemas.of(type("entry")), "a generic type that is neither collection, map nor optional is described by its class");
    }

    @Test
    void everyScalarHasItsJsonType() {
        final JsonNode schema = McpSchemas.of(Scalars.class);
        final JsonNode p = schema.get("properties");
        for (final String integer : new String[]{"s", "b", "boxedShort", "boxedByte", "boxedLong", "boxedInt", "big"}) {
            assertEquals("integer", p.get(integer).get("type").asString(), integer);
        }
        for (final String number : new String[]{"f", "boxedFloat", "boxedDouble"}) {
            assertEquals("number", p.get(number).get("type").asString(), number);
        }
        assertEquals("boolean", p.get("boxedBoolean").get("type").asString());
        assertEquals("string", p.get("boxedChar").get("type").asString());
        assertEquals(12, schema.get("required").size());
    }

    @Test
    void descriptionsAndOptionalComponents() {
        final JsonNode schema = McpSchemas.of(Described.class);
        assertEquals("The customer's name", schema.at("/properties/name/description").asString());
        assertEquals("integer", schema.at("/properties/count/type").asString());
        assertEquals("number", schema.at("/properties/ratio/type").asString());
        assertEquals(List.of("name"), M.convertValue(schema.get("required"), List.class), "only what is not optional is required");
        assertEquals(List.of(), McpSchemas.required(String.class));
        assertEquals(List.of("name"), McpSchemas.required(Described.class));
    }

    @Test
    void aRecordWithOnlyOptionalComponentsHasNoRequiredList() {
        record AllOptional(Optional<String> a, OptionalLong b) {
        }
        final JsonNode schema = McpSchemas.of(AllOptional.class);
        assertEquals(false, schema.has("required"));
        assertEquals(false, schema.get("additionalProperties").asBoolean());
    }
}
