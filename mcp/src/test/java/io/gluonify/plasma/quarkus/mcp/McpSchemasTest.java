package io.gluonify.plasma.quarkus.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class McpSchemasTest {
    static final ObjectMapper M = new ObjectMapper();

    record Inner(String code) {}

    record Node(String name, List<Node> children) {}

    record All(boolean flag, long count, BigDecimal price, double ratio, char letter, int[] values, Set<String> labels, Map<String, Integer> weights,
            Inner inner, OptionalInt limit, Object anything) {}

    @Test
    void derivedFromARecord() {
        assertEquals(M.readTree("""
                {"type":"object","properties":{
                  "flag":{"type":"boolean"},"count":{"type":"integer"},"price":{"type":"number"},"ratio":{"type":"number"},"letter":{"type":"string"},
                  "values":{"type":"array","items":{"type":"integer"}},"labels":{"type":"array","items":{"type":"string"},"uniqueItems":true},
                  "weights":{"type":"object","additionalProperties":{"type":"integer"}},
                  "inner":{"type":"object","properties":{"code":{"type":"string"}},"required":["code"],"additionalProperties":false},
                  "limit":{"type":"integer"},"anything":{}},
                 "required":["flag","count","price","ratio","letter","values","labels","weights","inner","anything"],"additionalProperties":false}"""),
                McpSchemas.of(All.class));
    }

    @Test
    void aRecursiveRecordIsCut() {
        assertEquals(M.readTree("""
                {"type":"object","properties":{"name":{"type":"string"},"children":{"type":"array","items":{"type":"object"}}},
                 "required":["name","children"],"additionalProperties":false}"""), McpSchemas.of(Node.class));
    }
}
