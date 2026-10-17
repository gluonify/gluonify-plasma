package io.gluonify.plasma.crystallon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** JSON contract of the Crystallon records: what the client sends, what the server answers. */
class CrystallonRecordsTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void renderParamsOmitsTheNullFields() throws Exception {
        assertEquals("{}", json.writeValueAsString(new RenderParams(null, null, null, null, null, null)));
        assertEquals(json.readTree("{\"lang\":\"fr\",\"country\":\"CH\",\"currency\":\"CHF\",\"timezone\":\"Europe/Zurich\",\"dateStyle\":\"long\",\"numberStyle\":\"swiss\"}"),
                json.readTree(json.writeValueAsString(new RenderParams("fr", "CH", "CHF", "Europe/Zurich", "long", "swiss"))));
    }

    @Test
    void renderRequestNeverHasNullData() throws Exception {
        assertEquals(Map.of(), new RenderRequest(null, null).data());
        assertEquals("{\"data\":{}}", json.writeValueAsString(new RenderRequest(null, null)));
        assertEquals(json.readTree("{\"data\":{\"a\":1},\"params\":{\"lang\":\"de\"}}"),
                json.readTree(json.writeValueAsString(new RenderRequest(Map.of("a", 1), new RenderParams("de", null, null, null, null, null)))));
    }

    @Test
    void inlineRenderRequestCarriesTheTemplateAndNeverHasNullData() throws Exception {
        assertEquals(Map.of(), new InlineRenderRequest("invoice", null, null).data());
        assertEquals("{\"template\":\"invoice\",\"data\":{}}", json.writeValueAsString(new InlineRenderRequest("invoice", null, null)));
        assertEquals(json.readTree("{\"template\":{\"extends\":\"letter\",\"theme\":{\"primary\":\"#B00020\"}},\"data\":{\"a\":1},\"params\":{\"lang\":\"de\"}}"),
                json.readTree(json.writeValueAsString(new InlineRenderRequest(Map.of("extends", "letter", "theme", Map.of("primary", "#B00020")), Map.of("a", 1),
                        new RenderParams("de", null, null, null, null, null)))));
        assertThrows(NullPointerException.class, () -> new InlineRenderRequest(null, Map.of(), null));
    }

    @Test
    void checkReadsTheValidationAnswerAndIgnoresNewFields() throws Exception {
        final Check check = json.readValue("""
                {"valid":false,"pages":0,"warnings":["unknown path 'x'"],
                 "errors":[{"code":"CRYSTALLON-0014","message":"Unknown block","path":"body[3].blocks[0].text","later":true}],
                 "warningDetails":[{"code":null,"message":"unknown path 'x'","path":"body[0].text"}],"later":1}""", Check.class);

        assertFalse(check.valid());
        assertEquals(List.of("unknown path 'x'"), check.warnings());
        assertEquals(new Issue("CRYSTALLON-0014", "Unknown block", "body[3].blocks[0].text"), check.errors().get(0));
        assertNull(check.warningDetails().get(0).code());
    }

    @Test
    void checkListsAreNeverNullAndCannotBeChanged() throws Exception {
        final Check missing = json.readValue("{\"valid\":true,\"pages\":1}", Check.class);
        assertEquals(List.of(), missing.warnings());
        assertEquals(List.of(), missing.errors());
        assertEquals(List.of(), missing.warningDetails());

        final List<String> source = new ArrayList<>(List.of("w"));
        final Check copy = new Check(true, 1, source, null, null);
        source.add("changed later");
        assertEquals(List.of("w"), copy.warnings());
        assertThrows(UnsupportedOperationException.class, () -> copy.warnings().add("x"));
        assertTrue(new Check(true, 1, new ArrayList<>(java.util.Arrays.asList("a", null)), null, null).warnings().contains(null));
    }

    @Test
    void errorBodyReadsWithAndWithoutPath() throws Exception {
        assertEquals(new ErrorBody("CRYSTALLON-0014", "m", "header[0]"), json.readValue("{\"code\":\"CRYSTALLON-0014\",\"message\":\"m\",\"path\":\"header[0]\",\"more\":1}", ErrorBody.class));
        assertNull(json.readValue("{\"code\":\"CRYSTALLON-0004\",\"message\":\"m\"}", ErrorBody.class).path());
    }
}
