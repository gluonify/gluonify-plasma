package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.gluonify.plasma.crystallon.Check;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Le rendu d'un modèle envoyé avec la demande ({@code POST /v1/render}), contre le faux Crystallon : mêmes en-têtes, erreurs et bornes que les modèles stockés. */
class InlineTemplateTest {
    private FakeCrystallon fake;
    private final PayloadCodec codec = PayloadCodec.detect();

    @BeforeEach
    void start() {
        fake = new FakeCrystallon();
    }

    @AfterEach
    void stop() {
        fake.close();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body() {
        return codec.read(fake.last().body(), Map.class);
    }

    private CrystallonClient client() {
        return fake.client().build();
    }

    // ---- modèle prédéfini

    @Test
    void rendersAPredefinedTemplateByName() {
        final RenderedPdf pdf = client().inline("invoice").render(Map.of("customer", "Ada"));

        final FakeCrystallon.Call call = fake.last();
        assertEquals("POST", call.method());
        assertEquals("/v1/render", call.rawPath());
        assertEquals("pdfa=true", call.rawQuery());
        assertEquals("application/pdf", call.header("Accept"));
        assertEquals("application/json", call.header("Content-Type"));
        assertEquals(Map.of("template", "invoice", "data", Map.of("customer", "Ada"), "params", Map.of()), body());
        assertArrayEquals(FakeCrystallon.PDF, pdf.bytes());
        assertEquals(2, pdf.pages());
        assertEquals(1, pdf.warnings());
        assertEquals(Boolean.TRUE, pdf.pdfa());
    }

    @Test
    void thePredefinedNameIsStrippedAndNeverPartOfThePath() {
        client().inline("  quote \n").render(Map.of());

        assertEquals("/v1/render?pdfa=true", fake.last().target());
        assertEquals("quote", body().get("template"));
    }

    @Test
    void sendsTheOptionsAsParamsAndPdfaAsTheQuery() {
        final RenderOptions options = RenderOptions.builder().lang("fr").country("CH").currency("CHF").timezone("Europe/Zurich").dateStyle("long")
                .numberStyle("swiss").pdfa(false).build();

        client().inline("letter").render(Map.of("a", 1), options);

        assertEquals("/v1/render?pdfa=false", fake.last().target());
        assertEquals(Map.of("lang", "fr", "country", "CH", "currency", "CHF", "timezone", "Europe/Zurich", "dateStyle", "long", "numberStyle", "swiss"),
                body().get("params"));
    }

    @Test
    void omitsTheParamsThatAreNotSoThatTheTemplateDefaultsApply() {
        client().inline("invoice").render(Map.of(), RenderOptions.builder().lang("de").build());

        assertEquals(Map.of("lang", "de"), body().get("params"));
    }

    @Test
    void nullDataIsAnEmptyObjectSoThatTheServerDoesNotRenderTheSample() {
        client().inline("invoice").render(null);

        assertEquals(Map.of(), body().get("data"));
        assertTrue(fake.last().text().contains("\"data\":{}"), fake.last().text());
    }

    @Test
    void nullOptionsAreTheDefaultOptions() {
        client().inline("invoice").render(Map.of(), null);
        assertEquals("/v1/render?pdfa=true", fake.last().target());
        assertEquals(Map.of(), body().get("params"));

        fake.reply(FakeCrystallon.Reply.json(200, "{\"valid\":true,\"pages\":1,\"warnings\":[],\"errors\":[],\"warningDetails\":[]}"));
        client().inline("invoice").validate(Map.of(), null);
        assertEquals(Map.of(), body().get("params"));
    }

    // ---- modèle envoyé

    @Test
    void sendsATemplateObjectAsTheTemplateOfTheBody() {
        final Map<String, Object> template = new LinkedHashMap<>();
        template.put("extends", "invoice");
        template.put("theme", Map.of("primary", "#005eb8"));
        // LinkedHashMap et non Map.of : l'ordre de Map.of change à chaque JVM, et « "size":12, » exige que size ne soit pas la dernière clé
        final Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "text");
        text.put("text", "{{ customer }}");
        text.put("size", 12);
        text.put("ratio", 0.5);
        text.put("bold", true);
        template.put("body", List.of(text));

        client().inline(template).render(Map.of("customer", "Ada"));

        assertEquals("/v1/render?pdfa=true", fake.last().target());
        assertEquals(template, body().get("template"));
        assertEquals(Map.of("customer", "Ada"), body().get("data"));
        assertTrue(fake.last().text().contains("\"size\":12,"), fake.last().text());
        assertTrue(fake.last().text().contains("\"ratio\":0.5"), fake.last().text());
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Theme(String primary, String logo) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OwnTemplate(@JsonProperty("extends") String parent, Theme theme, Map<String, String> params) {
    }

    @Test
    void sendsARecordTemplateLikeAPayload() {
        client().inline(new OwnTemplate("letter", new Theme("#B00020", null), null)).render(Map.of());

        assertEquals(Map.of("extends", "letter", "theme", Map.of("primary", "#B00020")), body().get("template"));
        assertFalse(fake.last().text().contains("logo"), fake.last().text());
    }

    @Test
    void aTemplateObjectIsFrozenWhenTheInlineTemplateIsMade() {
        final Map<String, Object> template = new LinkedHashMap<>();
        final List<Object> body = new ArrayList<>(List.of("one"));
        template.put("extends", "invoice");
        template.put("body", body);
        final InlineTemplate inline = client().inline(template);

        template.put("extends", "letter");
        template.put("added", true);
        body.add("two");
        inline.render(Map.of());

        assertEquals(Map.of("extends", "invoice", "body", List.of("one")), body().get("template"));
        inline.render(Map.of());
        assertEquals(Map.of("extends", "invoice", "body", List.of("one")), body().get("template"));
    }

    @Test
    void makingAnInlineTemplateCallsNothing() {
        client().inline("invoice");
        client().inline(Map.of("extends", "invoice"));

        assertTrue(fake.calls().isEmpty());
    }

    @Test
    void aTemplateThatIsAJsonNodeOfTheCodecIsSent() {
        assumeTrue(JacksonClasspath.jackson3() || JacksonClasspath.jackson2());
        if (codec.getClass().getSimpleName().equals("Jackson3Codec")) {
            Jackson3Node.check(this);
        }
        else {
            Jackson2Node.check(this);
        }
    }

    /** Isolés : le test ne référence Jackson 3 (ou 2) que dans ces classes. */
    private static final class Jackson3Node {
        static void check(final InlineTemplateTest t) {
            final tools.jackson.databind.json.JsonMapper mapper = tools.jackson.databind.json.JsonMapper.builder().build();
            final tools.jackson.databind.JsonNode template = mapper.readTree("{\"extends\":\"invoice\",\"theme\":{\"primary\":\"#005eb8\"},\"n\":[1,2.5]}");
            t.client().inline(template).render(Map.of());
            assertEquals(Map.of("extends", "invoice", "theme", Map.of("primary", "#005eb8"), "n", List.of(1, 2.5)), t.body().get("template"));
        }
    }

    private static final class Jackson2Node {
        static void check(final InlineTemplateTest t) {
            try {
                final com.fasterxml.jackson.databind.JsonNode template = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree("{\"extends\":\"invoice\",\"theme\":{\"primary\":\"#005eb8\"},\"n\":[1,2.5]}");
                t.client().inline(template).render(Map.of());
                assertEquals(Map.of("extends", "invoice", "theme", Map.of("primary", "#005eb8"), "n", List.of(1, 2.5)), t.body().get("template"));
            }
            catch (final com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    @Test
    void refusesWhatIsNeitherAPresetNameNorATemplateObject() {
        final CrystallonClient client = client();

        assertThrows(IllegalArgumentException.class, () -> client.inline(null));
        assertThrows(IllegalArgumentException.class, () -> client.inline(""));
        assertThrows(IllegalArgumentException.class, () -> client.inline("  \n"));
        assertThrows(IllegalArgumentException.class, () -> client.inline(List.of(1, 2)));
        assertThrows(IllegalArgumentException.class, () -> client.inline(42));
        assertThrows(IllegalArgumentException.class, () -> client.inline(true));
        assertTrue(fake.calls().isEmpty());
    }

    @Test
    void jsonWrittenInAStringIsRefusedWithAHint() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> client().inline("{\"extends\":\"invoice\"}"));

        assertTrue(e.getMessage().contains("Map"), e.getMessage());
        assertTrue(fake.calls().isEmpty());
    }

    // ---- validation

    @Test
    void validatesAndReadsTheCheck() {
        fake.reply(FakeCrystallon.Reply.json(200, """
                {"valid":false,"pages":0,"warnings":["unknown path 'x'"],
                 "errors":[{"code":"CRYSTALLON-0014","message":"Unknown block","path":"body[3].blocks[0].text"}],
                 "warningDetails":[{"code":null,"message":"unknown path 'x'","path":"body[0].text"}],"extra":"ignored"}"""));

        final Check check = client().inline(Map.of("extends", "invoice")).validate(Map.of("a", 1));

        assertEquals("/v1/render/validate", fake.last().target());
        assertEquals("application/json", fake.last().header("Accept"));
        assertEquals(Map.of("extends", "invoice"), body().get("template"));
        assertEquals(Map.of("a", 1), body().get("data"));
        assertFalse(check.valid());
        assertEquals("CRYSTALLON-0014", check.errors().get(0).code());
        assertEquals("body[3].blocks[0].text", check.errors().get(0).path());
        assertNull(check.warningDetails().get(0).code());
    }

    @Test
    void validateSendsTheParamsButNoPdfaQuery() {
        fake.reply(FakeCrystallon.Reply.json(200, "{\"valid\":true,\"pages\":2,\"warnings\":[],\"errors\":[],\"warningDetails\":[]}"));

        final Check check = client().inline("quote").validate(Map.of(), RenderOptions.builder().lang("fr").pdfa(false).build());

        assertEquals("/v1/render/validate", fake.last().target());
        assertEquals("quote", body().get("template"));
        assertEquals(Map.of("lang", "fr"), body().get("params"));
        assertTrue(check.valid());
        assertEquals(2, check.pages());
    }

    @Test
    void anUnreadableValidationAnswerIsACrystallonException() {
        fake.reply(FakeCrystallon.Reply.json(200, "[1,2]"));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> client().inline("invoice").validate(Map.of()));
        assertEquals(200, e.status());
        assertNull(e.code());
    }

    // ---- erreurs, jeton, bornes : le code des modèles stockés

    @Test
    void anUnknownPresetIsACrystallonExceptionWithItsCodeAndPath() {
        fake.reply(FakeCrystallon.Reply.json(404, "{\"code\":\"CRYSTALLON-0004\",\"message\":\"Unknown predefined template 'invoise'\",\"path\":\"template\"}"));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> client().inline("invoise").render(Map.of()));

        assertEquals(404, e.status());
        assertEquals("CRYSTALLON-0004", e.code());
        assertEquals("template", e.path());
    }

    @Test
    void anInvalidTemplateKeepsThePathOfTheProblem() {
        fake.reply(FakeCrystallon.Reply.json(422, "{\"code\":\"CRYSTALLON-0014\",\"message\":\"Unknown block type 'foo'\",\"path\":\"body[3].blocks[0].text\"}"));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> client().inline(Map.of("body", List.of())).render(Map.of()));

        assertEquals(422, e.status());
        assertEquals("body[3].blocks[0].text", e.path());
    }

    @Test
    void unauthorizedAndForbiddenWithoutABodyHaveNoCode() {
        fake.reply(FakeCrystallon.Reply.empty(401));
        final CrystallonException unauthorized = assertThrows(CrystallonException.class,
                () -> fake.client().tokens(CrystallonTokens.fixed("secret-token")).build().inline("invoice").render(Map.of()));
        assertEquals(401, unauthorized.status());
        assertNull(unauthorized.code());
        assertFalse(unauthorized.getMessage().contains("secret-token"));

        fake.reply(FakeCrystallon.Reply.empty(403));
        final CrystallonException forbidden = assertThrows(CrystallonException.class, () -> client().inline("invoice").validate(Map.of()));
        assertEquals(403, forbidden.status());
        assertTrue(forbidden.getMessage().contains("gluonify:crystallon:render"));
    }

    @Test
    void sendsTheBearerToken() {
        fake.client().tokens(CrystallonTokens.fixed("t0k")).build().inline("invoice").render(Map.of());

        assertEquals("Bearer t0k", fake.last().header("Authorization"));
    }

    @Test
    void aBodyThatIsNotAPdfIsRefused() {
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", "<html>not a pdf</html>".getBytes()));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> client().inline("invoice").render(Map.of()));
        assertEquals(200, e.status());
        assertTrue(e.getMessage().contains("did not return a PDF"));
    }

    @Test
    void aPdfLargerThanTheLimitIsRefused() {
        final byte[] big = new byte[200 * 1024];
        java.util.Arrays.fill(big, (byte) 'x');
        System.arraycopy("%PDF-1.4\n".getBytes(), 0, big, 0, 9);
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", big));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().maxPdfBytes(100 * 1024).build().inline("invoice").render(Map.of()));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
    }

    @Test
    void aBaseUrlWithAPrefixIsKept() {
        CrystallonClient.builder().baseUrl(fake.url() + "/crystallon/").build().inline("invoice").render(Map.of());

        assertEquals("/crystallon/v1/render", fake.last().rawPath());
    }

    @Test
    void anInlineTemplateIsReusableAndSharesNothingWithTheStoredRoutes() {
        final InlineTemplate inline = client().inline("invoice");

        inline.render(Map.of());
        fake.reply(FakeCrystallon.Reply.json(200, "{\"valid\":true,\"pages\":1,\"warnings\":[],\"errors\":[],\"warningDetails\":[]}"));
        inline.validate(Map.of());
        fake.reply(FakeCrystallon.Reply.pdf());
        inline.render(Map.of("a", 1));
        client().byName("invoice").render(Map.of());

        assertEquals(List.of("/v1/render", "/v1/render/validate", "/v1/render", "/v1/templates/by-name/invoice/render"),
                fake.calls().stream().map(FakeCrystallon.Call::rawPath).toList());
        assertFalse(fake.calls().get(3).text().contains("\"template\""), fake.calls().get(3).text());
    }

    @Test
    void servesRealPayloadsWithDatesAndDecimals() {
        client().inline("invoice").render(TestPayloads.invoice(), RenderOptions.builder().lang("fr").build());

        final String sent = fake.last().text();
        assertTrue(sent.contains("\"date\":\"2026-10-10\""), sent);
        assertTrue(sent.contains("\"total\":1234.50"), sent);
        assertTrue(sent.contains("\"invoice-number\":\"2026-001\""), sent);
    }

    @Test
    void toStringTellsWhichTemplateWithoutPrintingIt() {
        assertEquals("InlineTemplate[preset invoice]", client().inline("invoice").toString());
        assertEquals("InlineTemplate[object, 2 keys]", client().inline(Map.of("extends", "invoice", "secret", "do-not-print")).toString());
    }
}
