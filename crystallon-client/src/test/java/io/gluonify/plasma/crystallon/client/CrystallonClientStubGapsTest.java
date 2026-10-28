package io.gluonify.plasma.crystallon.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The Crystallon client against a stub that answers whatever the test says: odd bodies, headers, errors, tokens, interruptions, builder refusals. */
class CrystallonClientStubGapsTest {
    static final byte[] PDF = "%PDF-1.7 minimal".getBytes(StandardCharsets.US_ASCII);

    @TempDir
    Path dir;

    HttpServer server;
    volatile int status = 200;
    volatile byte[] body = PDF;
    final Map<String, String> headers = new LinkedHashMap<>();
    volatile CountDownLatch entered = new CountDownLatch(1);
    volatile CountDownLatch release;
    volatile String lastAuthorization;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    CrystallonClient.Builder client() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            lastAuthorization = ex.getRequestHeaders().getFirst("Authorization");
            ex.getRequestBody().readAllBytes();
            entered.countDown();
            final CountDownLatch wait = release;
            if (wait != null) {
                try {
                    wait.await(30, TimeUnit.SECONDS);
                }
                catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            headers.forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            ex.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                ex.getResponseBody().write(body);
            }
            ex.close();
        });
        server.start();
        return CrystallonClient.builder().baseUrl("http://127.0.0.1:" + server.getAddress().getPort());
    }

    static byte[] text(final String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    TemplateRef ref(final CrystallonClient.Builder builder) {
        return builder.build().byId("t1");
    }

    // --- what a PDF must look like ---

    @Test
    void aBodyThatIsNotAPdfIsRefusedWhateverItsFirstBytes() throws Exception {
        final TemplateRef t = ref(client());
        for (final String notPdf : new String[]{"", "%PD", "%PDX-1.7", "XPDF-1.7", "%XDF-1.7", "%PXF-1.7"}) {
            body = text(notPdf);
            final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()), notPdf);
            assertEquals("Crystallon did not return a PDF", e.getMessage(), notPdf);
        }
    }

    @Test
    void aPdfLargerThanTheLimitIsRefused() throws Exception {
        final TemplateRef t = ref(client().maxPdfBytes(8));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
        assertTrue(e.getMessage().contains("larger than the limit of 8 bytes"), e.getMessage());
    }

    @Test
    void theHeadersOfTheAnswerBecomePagesWarningsAndPdfaVerdict() throws Exception {
        final TemplateRef t = ref(client());
        headers.put("X-Crystallon-Pages", " 3 ");
        headers.put("X-Crystallon-Warnings", "-4");
        headers.put("X-Crystallon-PDFA", "VALID");
        RenderedPdf pdf = t.render(Map.of());
        assertEquals(3, pdf.pages());
        assertEquals(0, pdf.warnings(), "a negative count is no count");
        assertEquals(Boolean.TRUE, pdf.pdfa());
        headers.put("X-Crystallon-Pages", "many");
        headers.put("X-Crystallon-PDFA", "false");
        pdf = t.render(Map.of());
        assertEquals(0, pdf.pages(), "unreadable count");
        assertEquals(Boolean.FALSE, pdf.pdfa());
        headers.put("X-Crystallon-PDFA", "invalid");
        assertEquals(Boolean.FALSE, t.render(Map.of()).pdfa());
        headers.put("X-Crystallon-PDFA", "true");
        assertEquals(Boolean.TRUE, t.render(Map.of()).pdfa());
        headers.put("X-Crystallon-PDFA", "perhaps");
        assertNull(t.render(Map.of()).pdfa(), "something else: no verdict");
        headers.clear();
        assertNull(t.render(Map.of()).pdfa());
    }

    // --- errors ---

    @Test
    void anErrorAnswerIsReadFromJsonOrReducedToItsStatus() throws Exception {
        final TemplateRef t = ref(client());
        status = 400;
        body = text("{\"code\":\"CRYSTALLON-0001\",\"message\":\"bad template\",\"path\":\"/body\"}");
        CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
        assertEquals("CRYSTALLON-0001", e.code());
        assertEquals("bad template", e.getMessage());
        assertEquals(400, e.status());

        body = text("  \n\t{\"code\":\"ONLY-CODE\"}");
        status = 403;
        e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
        assertEquals("ONLY-CODE", e.code());
        assertTrue(e.getMessage().contains("forbids the call (HTTP 403)"), "no message: the default of the status, " + e.getMessage());

        status = 401;
        body = text("{\"message\":\"who are you\"}");
        assertEquals("who are you", assertThrows(CrystallonException.class, () -> t.render(Map.of())).getMessage());
        body = text("{\"code\":\"X\"}");
        assertTrue(assertThrows(CrystallonException.class, () -> t.render(Map.of())).getMessage().contains("refused the call (HTTP 401)"));
        status = 502;
        assertEquals("Crystallon answered HTTP 502", assertThrows(CrystallonException.class, () -> t.render(Map.of())).getMessage());
    }

    @Test
    void anErrorBodyThatIsNotAnErrorIsKeptAsItsStatus() throws Exception {
        final TemplateRef t = ref(client());
        status = 500;
        for (final String proxy : new String[]{"<html>bad gateway</html>", "", "   ", "{}", "{\"unrelated\":1}", "{broken json"}) {
            body = text(proxy);
            final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()), proxy);
            assertEquals(500, e.status(), proxy);
            assertNull(e.code(), proxy);
            assertEquals("Crystallon answered HTTP 500", e.getMessage(), proxy);
        }
    }

    @Test
    void anErrorBodyLargerThanTheLimitIsIgnored() throws Exception {
        final TemplateRef t = ref(client());
        status = 500;
        body = new byte[(int) BoundedBody.ERROR_LIMIT + 10];
        java.util.Arrays.fill(body, (byte) '{');
        final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
        assertEquals("Crystallon answered HTTP 500", e.getMessage());
    }

    // --- validation answers ---

    @Test
    void aValidationAnswerThatCannotBeReadIsReported() throws Exception {
        final TemplateRef t = ref(client());
        body = text("not json at all");
        final CrystallonException unreadable = assertThrows(CrystallonException.class, () -> t.validate(Map.of()));
        assertEquals("The validation answer of Crystallon is not readable", unreadable.getMessage());
        assertNotNull(unreadable.getCause());
        body = text("null");
        final CrystallonException empty = assertThrows(CrystallonException.class, () -> t.validate(Map.of()));
        assertEquals("The validation answer of Crystallon is not readable", empty.getMessage());
        assertNull(empty.getCause());
    }

    @Test
    void aValidationAnswerLargerThanTheLimitIsNotReadable() throws Exception {
        final TemplateRef t = ref(client().maxPdfBytes(4));
        body = text("{\"valid\":true,\"pages\":1}");
        assertThrows(CrystallonException.class, () -> t.validate(Map.of()));
    }

    @Test
    void aValidationErrorAnswerIsAnException() throws Exception {
        final TemplateRef t = ref(client());
        status = 404;
        body = text("{\"code\":\"CRYSTALLON-0009\",\"message\":\"no such template\"}");
        assertEquals("no such template", assertThrows(CrystallonException.class, () -> t.validate(Map.of())).getMessage());
    }

    // --- tokens ---

    @Test
    void aBlankTokenSendsNoAuthorizationAndARealOneIsSentAsBearer() throws Exception {
        final CrystallonClient.Builder builder = client();
        ref(builder.tokens(() -> Optional.of("  "))).render(Map.of());
        assertNull(lastAuthorization);
        ref(builder.tokens(() -> Optional.of("abc"))).render(Map.of());
        assertEquals("Bearer abc", lastAuthorization);
    }

    @Test
    void aTokenThatCannotBeAHeaderValueIsRefused() throws Exception {
        final TemplateRef t = ref(client().tokens(() -> Optional.of("bad\ntoken")));
        final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
        assertEquals("The Crystallon token is not a valid HTTP header value", e.getMessage());
        assertEquals(0, e.status());
    }

    // --- the network ---

    @Test
    void anUnreachableCrystallonIsReportedWithTheKindOfFailure() {
        final TemplateRef template = CrystallonClient.builder().baseUrl("http://127.0.0.1:1").build().byId("t");
        final Map<String, Object> data = Map.of();
        final CrystallonException e = assertThrows(CrystallonException.class, () -> template.render(data));
        assertTrue(e.getMessage().startsWith("Crystallon is unreachable ("), e.getMessage());
    }

    @Test
    void aSlowCrystallonTimesOut() throws Exception {
        release = new CountDownLatch(1);
        final TemplateRef t = ref(client().timeout(Duration.ofMillis(200)));
        try {
            final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
            assertTrue(e.getMessage().startsWith("Crystallon did not answer within"), e.getMessage());
        }
        finally {
            release.countDown();
        }
    }

    @Test
    void anInterruptedCallKeepsTheInterruptionForTheCaller() throws Exception {
        release = new CountDownLatch(1);
        final TemplateRef t = ref(client());
        final Thread caller = Thread.currentThread();
        final Thread interrupter = new Thread(() -> {
            try {
                entered.await(30, TimeUnit.SECONDS);
            }
            catch (final InterruptedException e) {
                return;
            }
            caller.interrupt();
        });
        interrupter.start();
        try {
            final CrystallonException e = assertThrows(CrystallonException.class, () -> t.render(Map.of()));
            assertEquals("The call to Crystallon was interrupted", e.getMessage());
            assertTrue(Thread.currentThread().isInterrupted());
        }
        finally {
            Thread.interrupted();
            release.countDown();
            interrupter.join();
        }
    }

    // --- templates and options ---

    @Test
    void nullOptionsMeanTheDefaultsAndReferencesPrintTheirVersion() throws Exception {
        final TemplateRef t = ref(client());
        assertArrayEquals(PDF, t.render(Map.of(), null).bytes());
        body = text("{\"valid\":true,\"pages\":2}");
        assertTrue(t.validate(Map.of(), null).valid());
        assertEquals("TemplateRef[/v1/templates/t1]", t.toString());
        assertEquals("TemplateRef[/v1/templates/t1, version=3]", t.version(3).toString());
        assertThrows(IllegalArgumentException.class, () -> t.version(0));
    }

    @Test
    void aRenderedPdfComparesByContentAndPrintsItsSize() {
        final RenderedPdf a = new RenderedPdf(PDF.clone(), 1, 0, null);
        assertEquals(a, new RenderedPdf(PDF.clone(), 1, 0, null));
        assertEquals(a.hashCode(), new RenderedPdf(PDF.clone(), 1, 0, null).hashCode());
        assertNotEquals(a, new RenderedPdf(PDF.clone(), 2, 0, null));
        assertNotEquals(a, new RenderedPdf(PDF.clone(), 1, 1, null));
        assertNotEquals(a, new RenderedPdf(PDF.clone(), 1, 0, Boolean.TRUE));
        assertNotEquals(a, new RenderedPdf(new byte[]{1}, 1, 0, null));
        final Object other = "a string";
        assertNotEquals(a, other);
        assertEquals("RenderedPdf[bytes=16 bytes, pages=1, warnings=0, pdfa=null]", a.toString());
        assertEquals("RenderedPdf[bytes=null, pages=0, warnings=0, pdfa=true]", new RenderedPdf(null, 0, 0, Boolean.TRUE).toString());
    }

    @Test
    void renderOptionsPrintTheirParameters() {
        final RenderOptions o = RenderOptions.builder().lang("fr").country("CH").currency("CHF").timezone("Europe/Zurich").dateStyle("iso").numberStyle("swiss").pdfa(false).build();
        assertTrue(o.toString().contains("pdfa=false") && o.toString().contains("fr") && o.toString().contains("CHF"), o.toString());
        assertFalse(o.pdfa());
        assertTrue(RenderOptions.DEFAULT.pdfa());
    }

    // --- inline templates ---

    @Test
    void anInlineTemplateIsNotAJsonStringAnArrayNorNull() throws Exception {
        final CrystallonClient c = client().build();
        assertThrows(IllegalArgumentException.class, () -> c.inline(null));
        assertThrows(IllegalArgumentException.class, () -> c.inline("   "));
        assertThrows(IllegalArgumentException.class, () -> c.inline("{\"a\":1}"));
        assertThrows(IllegalArgumentException.class, () -> c.inline("[1]"));
        final java.util.List<Integer> array = java.util.List.of(1, 2);
        assertThrows(IllegalArgumentException.class, () -> c.inline(array), "an array is not a template object");
        assertNotNull(c.inline("  invoice "));
        assertNotNull(c.inline(Map.of("title", "x")));
    }

    @Test
    void aTemplateThatSerializesToNullIsRefused() throws Exception {
        final PayloadCodec nullCodec = new PayloadCodec() {
            @Override
            public byte[] write(final Object value) {
                return text("null");
            }

            @Override
            public <T> T read(final byte[] json, final Class<T> type) {
                return null;
            }
        };
        final CrystallonClient c = client().json(nullCodec).build();
        final Object template = new Object();
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> c.inline(template));
        assertTrue(e.getMessage().contains("not null"), e.getMessage());
    }

    // --- builder and factories ---

    @Test
    void theBuilderRefusesWhatCannotWork() {
        final CrystallonClient.Builder b = CrystallonClient.builder();
        assertThrows(NullPointerException.class, () -> b.baseUrl((String) null));
        assertThrows(NullPointerException.class, () -> b.baseUrl((URI) null));
        for (final String bad : new String[]{"ftp://host", "/relative", "http:///nohost", "http://host/x?q=1", "http://host/x#frag", "host:80"}) {
            assertThrows(IllegalArgumentException.class, () -> b.baseUrl(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> b.timeout(Duration.ZERO));
        final Duration negative = Duration.ofSeconds(-1);
        assertThrows(IllegalArgumentException.class, () -> b.timeout(negative));
        assertThrows(IllegalArgumentException.class, () -> b.maxPdfBytes(0));
        assertThrows(NullPointerException.class, () -> b.tokens(null));
        assertThrows(NullPointerException.class, () -> b.json(null));
        assertThrows(NullPointerException.class, () -> b.httpClient(null));
        assertThrows(NullPointerException.class, () -> b.endpoint(null));
        assertThrows(IllegalStateException.class, b::build);
    }

    @Test
    void aClientFromAnEndpointUsesItsAddressAndItsToken() throws Exception {
        final CrystallonClient.Builder viaServer = client();
        final Endpoint endpoint = new Endpoint("crystallon", URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), () -> "from-endpoint", Endpoint.Source.OVERRIDE, null, null);
        CrystallonClient.builder().endpoint(endpoint).build().byId("t").render(Map.of());
        assertEquals("Bearer from-endpoint", lastAuthorization);
        assertNotNull(viaServer);
    }

    @Test
    void aClientIsCreatedFromAServiceResolutionOrTheEnvironment() throws Exception {
        client();
        final String url = "http://127.0.0.1:" + server.getAddress().getPort();
        final Map<String, String> props = new HashMap<>(Map.of("GLUONIFY_SERVICE_CRYSTALLON_URL", url, "GLUONIFY_CRYSTALLON_TOKEN", "svc-token"));
        final GluonifyServices services = GluonifyServices.builder().home(dir).environment(k -> null).properties(props::get).build();
        CrystallonClient.create(services).byId("t").render(Map.of());
        assertEquals("Bearer svc-token", lastAuthorization);

        final Map<String, String> env = new HashMap<>(Map.of("GLUONIFY_SERVICE_CRYSTALLON_URL", " " + url + " ", "GLUONIFY_CRYSTALLON_TOKEN", "env-token"));
        CrystallonClient.fromEnvironment(env::get).byId("t").render(Map.of());
        assertEquals("Bearer env-token", lastAuthorization);
        for (final String missing : new String[]{null, "", "  "}) {
            final Map<String, String> none = new HashMap<>();
            if (missing != null) {
                none.put("GLUONIFY_SERVICE_CRYSTALLON_URL", missing);
            }
            assertThrows(IllegalStateException.class, () -> CrystallonClient.fromEnvironment(none::get), String.valueOf(missing));
        }
    }

    @Test
    void theProcessWideFactoriesUseTheRealEnvironmentAndInstance() throws Exception {
        client();
        final String url = "http://127.0.0.1:" + server.getAddress().getPort();
        System.setProperty("GLUONIFY_SERVICE_CRYSTALLON_URL", url);
        try {
            CrystallonClient.create().byId("t").render(Map.of());
        }
        finally {
            System.clearProperty("GLUONIFY_SERVICE_CRYSTALLON_URL");
        }
        if (System.getenv("GLUONIFY_SERVICE_CRYSTALLON_URL") == null) {
            assertThrows(IllegalStateException.class, CrystallonClient::fromEnvironment);
        }
    }

    // --- token sources ---

    @Test
    void tokenSourcesBehaveAsTheirNamesSay() throws Exception {
        assertTrue(CrystallonTokens.none().token().isEmpty());
        assertEquals("a", CrystallonTokens.fixed("  a ").token().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> CrystallonTokens.fixed("  "));
        assertThrows(NullPointerException.class, () -> CrystallonTokens.fixed(null));
        assertEquals("fw", CrystallonTokens.forwarding(() -> " fw ").token().orElseThrow());
        assertTrue(CrystallonTokens.forwarding(() -> null).token().isEmpty());
        assertTrue(CrystallonTokens.forwarding(() -> "  ").token().isEmpty());

        final Path file = dir.resolve("token");
        final CrystallonTokens fromFile = CrystallonTokens.file(file);
        assertTrue(fromFile.token().isEmpty(), "not dropped yet");
        Files.writeString(file, "  from-file \n");
        assertEquals("from-file", fromFile.token().orElseThrow());
        Files.writeString(file, " ");
        assertTrue(fromFile.token().isEmpty());
        final CrystallonException unreadable = assertThrows(CrystallonException.class, CrystallonTokens.file(dir)::token);
        assertTrue(unreadable.getMessage().contains("cannot read the Crystallon token file"), unreadable.getMessage());
    }

    @Test
    void theEnvironmentDecidesWhichTokenSourceIsUsed() throws Exception {
        final Path file = Files.writeString(dir.resolve("t"), "file-token");
        assertEquals("env", CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN", "env", "GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token().orElseThrow());
        assertEquals("file-token", CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN", " ", "GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token().orElseThrow());
        assertTrue(CrystallonTokens.fromEnvironment(Map.of("GLUONIFY_CRYSTALLON_TOKEN_FILE", " ")::get).token().isEmpty());
        assertTrue(CrystallonTokens.fromEnvironment(Map.<String, String>of()::get).token().isEmpty());
        assertEquals("file-token", CrystallonTokens.platform(Map.of("GLUONIFY_CRYSTALLON_TOKEN_FILE", file.toString())::get).token().orElseThrow());
        final Map<String, String> blankFile = Map.of("GLUONIFY_CRYSTALLON_TOKEN_FILE", " ");
        assertThrows(IllegalStateException.class, () -> CrystallonTokens.platform(blankFile::get));
        final Map<String, String> empty = Map.of();
        assertThrows(IllegalStateException.class, () -> CrystallonTokens.platform(empty::get));
        if (System.getenv("GLUONIFY_CRYSTALLON_TOKEN_FILE") == null) {
            assertThrows(IllegalStateException.class, CrystallonTokens::platform);
            assertNotNull(CrystallonTokens.fromEnvironment());
        }
    }

    @Test
    void aFailingEndpointTokenBecomesACrystallonException() {
        final Endpoint failing = new Endpoint("crystallon", URI.create("http://127.0.0.1:1"), () -> {
            throw new io.gluonify.plasma.services.GluonifyServicesException("run gluonify login test.lan");
        }, Endpoint.Source.DEVELOPER, null, "test.lan");
        final CrystallonTokens tokens = CrystallonTokens.of(failing);
        final CrystallonException e = assertThrows(CrystallonException.class, tokens::token);
        assertEquals("run gluonify login test.lan", e.getMessage());
        assertEquals(0, e.status());
        assertThrows(NullPointerException.class, () -> CrystallonTokens.of(null));
    }

    // --- path segments ---

    @Test
    void pathSegmentsAreEncodedAndDotsCannotNavigate() throws IOException {
        assertEquals("%2E", CrystallonClient.segment("."));
        assertEquals("%2E%2E", CrystallonClient.segment(".."));
        assertEquals("a%20b%2Fc%C3%A9", CrystallonClient.segment("a b/cé"));
        assertEquals("A-z_9.~", CrystallonClient.segment("A-z_9.~"));
        final CrystallonClient c = client().build();
        assertThrows(IllegalArgumentException.class, () -> c.byId(" "));
        assertThrows(IllegalArgumentException.class, () -> c.byName(null));
    }
}
