package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.crystallon.Check;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The client against a fake Crystallon: paths, bodies, headers, errors, limits. */
class CrystallonClientTest {
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

    // ---- rendu

    @Test
    void rendersByIdWithTheExactPathQueryAndBody() {
        final RenderedPdf pdf = fake.client().build().byId("3f2a-77").render(Map.of("customer", "Ada"));

        final FakeCrystallon.Call call = fake.last();
        assertEquals("POST", call.method());
        assertEquals("/v1/templates/3f2a-77/render", call.rawPath());
        assertEquals("pdfa=true", call.rawQuery());
        assertEquals("application/pdf", call.header("Accept"));
        assertEquals("application/json", call.header("Content-Type"));
        assertEquals(Map.of("data", Map.of("customer", "Ada"), "params", Map.of()), body());
        assertArrayEquals(FakeCrystallon.PDF, pdf.bytes());
    }

    @Test
    void rendersByNameEncodedAsAPathSegment() {
        final CrystallonClient client = fake.client().build();

        client.byName("Gluonify invoice").render(Map.of());
        assertEquals("/v1/templates/by-name/Gluonify%20invoice/render", fake.last().rawPath());

        client.byName("A/B é+c").render(Map.of());
        assertEquals("/v1/templates/by-name/A%2FB%20%C3%A9%2Bc/render", fake.last().rawPath());

        client.byName("..").render(Map.of());
        assertEquals("/v1/templates/by-name/%2E%2E/render", fake.last().rawPath());
    }

    @Test
    void asksForAPreciseVersionAndKeepsTheOriginalReferenceUntouched() {
        final TemplateRef current = fake.client().build().byName("Gluonify invoice");
        final TemplateRef pinned = current.version(7);

        pinned.render(Map.of());
        assertEquals("/v1/templates/by-name/Gluonify%20invoice/render?pdfa=true&version=7", fake.last().target());

        current.render(Map.of());
        assertEquals("/v1/templates/by-name/Gluonify%20invoice/render?pdfa=true", fake.last().target());

        assertThrows(IllegalArgumentException.class, () -> current.version(0));
    }

    @Test
    void sendsTheOptionsAsParamsAndPdfaAsTheQuery() {
        final RenderOptions options = RenderOptions.builder().lang("fr").country("CH").currency("CHF").timezone("Europe/Zurich").dateStyle("long")
                .numberStyle("swiss").pdfa(false).build();

        fake.client().build().byId("t1").render(Map.of("a", 1), options);

        assertEquals("pdfa=false", fake.last().rawQuery());
        assertEquals(Map.of("lang", "fr", "country", "CH", "currency", "CHF", "timezone", "Europe/Zurich", "dateStyle", "long", "numberStyle", "swiss"),
                body().get("params"));
    }

    @Test
    void omitsTheParamsThatAreNotSet() {
        fake.client().build().byId("t1").render(Map.of(), RenderOptions.builder().lang("de").build());

        assertEquals(Map.of("lang", "de"), body().get("params"));
        assertEquals("pdfa=true", fake.last().rawQuery());
    }

    @Test
    void nullDataIsAnEmptyObjectSoThatTheServerDoesNotRenderTheSample() {
        fake.client().build().byId("t1").render(null);

        assertEquals(Map.of(), body().get("data"));
        assertTrue(fake.last().text().contains("\"data\":{}"), fake.last().text());
    }

    @Test
    void readsTheResponseHeaders() {
        fake.reply(FakeCrystallon.Reply.pdf().with("X-Crystallon-Pages", "12").with("X-Crystallon-Warnings", "3").with("X-Crystallon-PDFA", "invalid"));
        final RenderedPdf pdf = fake.client().build().byId("t1").render(Map.of());

        assertEquals(12, pdf.pages());
        assertEquals(3, pdf.warnings());
        assertEquals(Boolean.FALSE, pdf.pdfa());

        fake.reply(FakeCrystallon.Reply.pdf());
        assertEquals(Boolean.TRUE, fake.client().build().byId("t1").render(Map.of()).pdfa());
    }

    @Test
    void missingOrBrokenHeadersGiveZeroAndNull() {
        fake.reply(FakeCrystallon.Reply.pdf().without("X-Crystallon-Pages").with("X-Crystallon-Warnings", "many").without("X-Crystallon-PDFA"));
        final RenderedPdf pdf = fake.client().build().byId("t1").render(Map.of());

        assertEquals(0, pdf.pages());
        assertEquals(0, pdf.warnings());
        assertNull(pdf.pdfa());
    }

    @Test
    void renderedPdfComparesByContentAndDoesNotPrintTheBytes() {
        final RenderedPdf a = new RenderedPdf(new byte[] {'%', 'P', 'D', 'F'}, 1, 0, true);
        final RenderedPdf b = new RenderedPdf(new byte[] {'%', 'P', 'D', 'F'}, 1, 0, true);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertEquals("RenderedPdf[bytes=4 bytes, pages=1, warnings=0, pdfa=true]", a.toString());
    }

    @Test
    void aBaseUrlWithAPrefixAndATrailingSlashIsKept() {
        CrystallonClient.builder().baseUrl(fake.url() + "/crystallon/").build().byId("t1").render(Map.of());

        assertEquals("/crystallon/v1/templates/t1/render", fake.last().rawPath());
    }

    // ---- validation

    @Test
    void validatesAndReadsTheCheck() {
        fake.reply(FakeCrystallon.Reply.json(200, """
                {"valid":false,"pages":0,"warnings":["unknown path 'x'"],
                 "errors":[{"code":"CRYSTALLON-0014","message":"Unknown block","path":"body[3].blocks[0].text"}],
                 "warningDetails":[{"code":null,"message":"unknown path 'x'","path":"body[0].text"}],"extra":"ignored"}"""));

        final Check check = fake.client().build().byName("Gluonify invoice").version(2).validate(Map.of("a", 1));

        assertEquals("/v1/templates/by-name/Gluonify%20invoice/render/validate?version=2", fake.last().target());
        assertEquals("application/json", fake.last().header("Accept"));
        assertEquals(Map.of("a", 1), body().get("data"));
        assertFalse(check.valid());
        assertEquals(List.of("unknown path 'x'"), check.warnings());
        assertEquals("CRYSTALLON-0014", check.errors().get(0).code());
        assertEquals("body[3].blocks[0].text", check.errors().get(0).path());
        assertNull(check.warningDetails().get(0).code());
        assertEquals("body[0].text", check.warningDetails().get(0).path());
    }

    @Test
    void validateByIdWithoutVersionHasNoQuery() {
        fake.reply(FakeCrystallon.Reply.json(200, "{\"valid\":true,\"pages\":2,\"warnings\":[],\"errors\":[],\"warningDetails\":[]}"));

        final Check check = fake.client().build().byId("t1").validate(Map.of(), RenderOptions.builder().lang("fr").build());

        assertEquals("/v1/templates/t1/render/validate", fake.last().target());
        assertEquals(Map.of("lang", "fr"), body().get("params"));
        assertTrue(check.valid());
        assertEquals(2, check.pages());
        assertEquals(List.of(), check.errors());
    }

    @Test
    void anUnreadableValidationAnswerIsACrystallonException() {
        fake.reply(FakeCrystallon.Reply.json(200, "[1,2]"));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").validate(Map.of()));
        assertEquals(200, e.status());
        assertNull(e.code());
    }

    // ---- erreurs

    @Test
    void anErrorBodyBecomesACrystallonException() {
        fake.reply(FakeCrystallon.Reply.json(422, "{\"code\":\"CRYSTALLON-0014\",\"message\":\"Unknown block type 'foo'\",\"path\":\"body[3].blocks[0].text\"}"));

        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));

        assertEquals(422, e.status());
        assertEquals("CRYSTALLON-0014", e.code());
        assertEquals("Unknown block type 'foo'", e.getMessage());
        assertEquals("body[3].blocks[0].text", e.path());
    }

    @Test
    void notFoundAndBadRequestKeepTheirCodes() {
        fake.reply(FakeCrystallon.Reply.json(404, "{\"code\":\"CRYSTALLON-0004\",\"message\":\"Template not found\"}"));
        final CrystallonException notFound = assertThrows(CrystallonException.class, () -> fake.client().build().byName("nope").render(Map.of()));
        assertEquals(404, notFound.status());
        assertEquals("CRYSTALLON-0004", notFound.code());
        assertNull(notFound.path());

        fake.reply(FakeCrystallon.Reply.json(400, "{\"code\":\"CRYSTALLON-0005\",\"message\":\"unsupported lang 'xx' (en, fr, es, it, de)\"}"));
        final CrystallonException bad = assertThrows(CrystallonException.class,
                () -> fake.client().build().byId("t1").validate(Map.of(), RenderOptions.builder().lang("xx").build()));
        assertEquals(400, bad.status());
        assertEquals("CRYSTALLON-0005", bad.code());
    }

    @Test
    void unauthorizedAndForbiddenWithoutABodyHaveNoCode() {
        fake.reply(FakeCrystallon.Reply.empty(401));
        final CrystallonException unauthorized = assertThrows(CrystallonException.class,
                () -> fake.client().tokens(CrystallonTokens.fixed("secret-token")).build().byId("t1").render(Map.of()));
        assertEquals(401, unauthorized.status());
        assertNull(unauthorized.code());
        assertNull(unauthorized.path());
        assertTrue(unauthorized.getMessage().contains("401"));
        assertFalse(unauthorized.getMessage().contains("secret-token"));

        fake.reply(FakeCrystallon.Reply.empty(403));
        final CrystallonException forbidden = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals(403, forbidden.status());
        assertNull(forbidden.code());
        assertTrue(forbidden.getMessage().contains("gluonify:crystallon:render"));
    }

    @Test
    void anErrorThatIsNotAnErrorBodyKeepsOnlyTheStatus() {
        fake.reply(FakeCrystallon.Reply.raw(502, "text/html", "<html>Bad gateway</html>".getBytes()));
        final CrystallonException html = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals(502, html.status());
        assertNull(html.code());

        fake.reply(FakeCrystallon.Reply.raw(500, "application/json", "{\"truncated\":".getBytes()));
        final CrystallonException broken = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals(500, broken.status());
        assertNull(broken.code());
    }

    @Test
    void anErrorBodyIsAlsoReadOn401() {
        fake.reply(FakeCrystallon.Reply.json(401, "{\"code\":\"CRYSTALLON-0001\",\"message\":\"Token expired\"}"));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals("CRYSTALLON-0001", e.code());
        assertEquals("Token expired", e.getMessage());
    }

    @Test
    void aRedirectIsNotFollowed() {
        fake.reply(FakeCrystallon.Reply.empty(302).with("Location", "http://127.0.0.1:1/elsewhere"));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals(302, e.status());
        assertEquals(1, fake.calls().size());
    }

    // ---- PDF rejected

    @Test
    void aBodyThatIsNotAPdfIsRefused() {
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", "<html>not a pdf</html>".getBytes()));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
        assertEquals(200, e.status());
        assertNull(e.code());
        assertTrue(e.getMessage().contains("did not return a PDF"));

        fake.reply(FakeCrystallon.Reply.empty(204));
        assertThrows(CrystallonException.class, () -> fake.client().build().byId("t1").render(Map.of()));
    }

    @Test
    void aPdfLargerThanTheLimitIsRefusedByContentLength() {
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", pdfOf(200 * 1024)));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> fake.client().maxPdfBytes(100 * 1024).build().byId("t1").render(Map.of()));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        assertEquals(200, e.status());
    }

    @Test
    void aChunkedPdfLargerThanTheLimitIsCutOff() {
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", pdfOf(3 * 1024 * 1024)).asChunked());
        final long start = System.nanoTime();
        final CrystallonException e = assertThrows(CrystallonException.class,
                () -> fake.client().maxPdfBytes(100 * 1024).timeout(Duration.ofSeconds(10)).build().byId("t1").render(Map.of()));
        assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8, "the limit must stop the read, not the timeout");
    }

    @Test
    void aPdfAtTheLimitIsAccepted() {
        final byte[] exact = pdfOf(1000);
        fake.reply(FakeCrystallon.Reply.raw(200, "application/pdf", exact).asChunked());
        assertArrayEquals(exact, fake.client().maxPdfBytes(1000).build().byId("t1").render(Map.of()).bytes());
    }

    // ---- network

    @Test
    void aSlowCrystallonTimesOut() {
        fake.reply(FakeCrystallon.Reply.pdf().delayed(2000));
        final CrystallonException e = assertThrows(CrystallonException.class,
                () -> fake.client().timeout(Duration.ofMillis(300)).build().byId("t1").render(Map.of()));
        assertEquals(0, e.status());
        assertNull(e.code());
        assertNotNull(e.getCause());
        assertTrue(e.getMessage().contains("did not answer"), e.getMessage());
    }

    @Test
    void anUnreachableCrystallonIsACrystallonException() {
        final String url = fake.url();
        fake.close();
        final CrystallonException e = assertThrows(CrystallonException.class,
                () -> CrystallonClient.builder().baseUrl(url).timeout(Duration.ofSeconds(2)).build().byId("t1").render(Map.of()));
        assertEquals(0, e.status());
        assertNull(e.code());
    }

    @Test
    void theHttpClientIsCreatedOnFirstUse() {
        final CrystallonClient client = fake.client().build();
        assertTrue(fake.calls().isEmpty());
        client.byId("t1").render(Map.of());
        client.byId("t1").render(Map.of());
        assertEquals(2, fake.calls().size());
    }

    @Test
    void aGivenHttpClientIsUsed() {
        final CountingHttpClient counting = new CountingHttpClient();

        fake.client().httpClient(counting).build().byId("t1").render(Map.of());

        assertEquals(1, counting.sent.get());
    }

    /** An HTTP client that counts the sends and delegates to the JDK client. */
    private static final class CountingHttpClient extends HttpClient {
        final AtomicInteger sent = new AtomicInteger();
        private final HttpClient real = HttpClient.newHttpClient();

        @Override public Optional<CookieHandler> cookieHandler() { return real.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return real.connectTimeout(); }
        @Override public Redirect followRedirects() { return real.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return real.proxy(); }
        @Override public SSLContext sslContext() { return real.sslContext(); }
        @Override public SSLParameters sslParameters() { return real.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return real.authenticator(); }
        @Override public Version version() { return real.version(); }
        @Override public Optional<Executor> executor() { return real.executor(); }

        @Override
        public <T> HttpResponse<T> send(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) throws IOException, InterruptedException {
            sent.incrementAndGet();
            return real.send(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request, final HttpResponse.BodyHandler<T> handler) {
            return real.sendAsync(request, handler);
        }

        @Override
        public <T> CompletableFuture<HttpResponse<T>> sendAsync(final HttpRequest request, final HttpResponse.BodyHandler<T> handler,
                                                                final HttpResponse.PushPromiseHandler<T> push) {
            return real.sendAsync(request, handler, push);
        }
    }

    // ---- payload JSON

    @Test
    void serializesARealPayloadWithDatesDecimalsAndLists() {
        fake.client().build().byName("Gluonify invoice").render(TestPayloads.invoice(), RenderOptions.builder().lang("fr").build());

        final String sent = fake.last().text();
        assertTrue(sent.contains("\"invoice-number\":\"2026-001\""), sent);
        assertTrue(sent.contains("\"date\":\"2026-10-10\""), sent);
        assertTrue(sent.contains("\"issuedAt\":\"2026-10-10T08:30:00Z\""), sent);
        assertTrue(sent.contains("\"total\":1234.50"), sent);
        assertTrue(sent.contains("\"price\":120.00"), sent);
        assertTrue(sent.contains("\"qty\":3"), sent);
        assertFalse(sent.contains("note"), sent);
        assertFalse(sent.contains("invoiceNumber"), sent);
        assertEquals("2026-001", ((Map<?, ?>) body().get("data")).get("invoice-number"));
        assertEquals(2, ((List<?>) ((Map<?, ?>) body().get("data")).get("items")).size());
    }

    private static byte[] pdfOf(final int size) {
        final byte[] b = new byte[size];
        java.util.Arrays.fill(b, (byte) 'x');
        System.arraycopy("%PDF-1.4\n".getBytes(), 0, b, 0, 9);
        return b;
    }
}
