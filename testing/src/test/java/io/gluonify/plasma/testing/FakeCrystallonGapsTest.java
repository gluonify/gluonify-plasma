package io.gluonify.plasma.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** The fake Crystallon's routes and refusals, over real HTTP, and the lifecycle of the test services around the system properties. */
class FakeCrystallonGapsTest {
    final HttpClient http = HttpClient.newHttpClient();

    HttpResponse<String> send(final FakeCrystallon fake, final String method, final String path, final String token) throws IOException, InterruptedException {
        final HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(fake.url() + path)).method(method, HttpRequest.BodyPublishers.ofString("{}"));
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownRouteIs404WhateverTheToken() throws Exception {
        try (FakeCrystallon fake = new FakeCrystallon("t")) {
            for (final String path : new String[]{"/", "/v1/templates/abc", "/v1/templates/a/b/render", "/render", "/v2/render", "/v1/templates/abc/render/extra"}) {
                final HttpResponse<String> r = send(fake, "POST", path, null);
                assertEquals(404, r.statusCode(), path);
                assertTrue(r.body().contains("CRYSTALLON-0028"), r.body());
            }
            assertEquals(404, send(fake, "GET", "/v1/render", "t").statusCode(), "only POST is a route");
            assertTrue(fake.renders().isEmpty(), "unknown routes are not kept");
        }
    }

    @Test
    void aKnownRouteNeedsTheRightToken() throws Exception {
        try (FakeCrystallon fake = new FakeCrystallon("secret")) {
            assertEquals(401, send(fake, "POST", "/v1/render", null).statusCode());
            assertEquals(401, send(fake, "POST", "/v1/render", "wrong").statusCode());
            assertTrue(fake.renders().isEmpty(), "a refused request is not kept");
            assertEquals(200, send(fake, "POST", "/v1/render", "secret").statusCode());
            assertEquals(1, fake.renders().size());
        }
    }

    @Test
    void aPrefixBeforeV1IsAcceptedAndPdfaIsAnnouncedOnlyWhenAsked() throws Exception {
        try (FakeCrystallon fake = new FakeCrystallon("t")) {
            final HttpResponse<String> plain = send(fake, "POST", "/crystallon/v1/templates/by-name/My%20Invoice/render?pdfa=false", "t");
            assertEquals(200, plain.statusCode());
            assertTrue(plain.headers().firstValue("X-Crystallon-PDFA").isEmpty());
            assertEquals("My Invoice", fake.lastRender().template());
            assertTrue(fake.lastRender().byName());
            final HttpResponse<String> pdfa = send(fake, "POST", "/v1/templates/t1/render?pdfa=true", "t");
            assertEquals("valid", pdfa.headers().firstValue("X-Crystallon-PDFA").orElseThrow());
            assertEquals("1", pdfa.headers().firstValue("X-Crystallon-Pages").orElseThrow());
            final HttpResponse<String> none = send(fake, "POST", "/v1/templates/t1/render", "t");
            assertTrue(none.headers().firstValue("X-Crystallon-PDFA").isEmpty());
        }
    }

    @Test
    void validationRoutesAnswerAValidCheck() throws Exception {
        try (FakeCrystallon fake = new FakeCrystallon("t")) {
            final HttpResponse<String> r = send(fake, "POST", "/v1/templates/t1/render/validate", "t");
            assertEquals(200, r.statusCode());
            assertTrue(r.body().contains("\"valid\":true"), r.body());
            assertEquals("validate", fake.lastRender().kind());
            assertEquals("validate", send(fake, "POST", "/v1/render/validate", "t").statusCode() == 200 ? fake.lastRender().kind() : "none");
            assertNull(fake.lastRender().template());
        }
    }

    @Test
    void failuresAreScheduledInOrderAndEscapedAndResetForgetsThem() throws Exception {
        try (FakeCrystallon fake = new FakeCrystallon("t")) {
            fake.failNext(422, "CODE-1", "say \"no\" \\ here").failNext(500, null, null);
            final HttpResponse<String> first = send(fake, "POST", "/v1/render", "t");
            assertEquals(422, first.statusCode());
            assertTrue(first.body().contains("say \\\"no\\\" \\\\ here"), first.body());
            final HttpResponse<String> second = send(fake, "POST", "/v1/render", "t");
            assertEquals(500, second.statusCode());
            assertTrue(second.body().contains("\"code\":\"\""), second.body());
            assertEquals(200, send(fake, "POST", "/v1/render", "t").statusCode(), "once each");
            fake.failNext(503, "C", "m");
            fake.reset();
            assertTrue(fake.renders().isEmpty());
            assertEquals(200, send(fake, "POST", "/v1/render", "t").statusCode(), "reset forgets the scheduled failure");
        }
    }

    @Test
    void lastRenderNeedsAtLeastOneRequest() {
        try (FakeCrystallon fake = new FakeCrystallon("t")) {
            final IllegalStateException e = assertThrows(IllegalStateException.class, fake::lastRender);
            assertEquals("the fake Crystallon received no request", e.getMessage());
        }
    }

    @Test
    void theParserReadsRoutesAndRefusesTheOthers() {
        assertNull(FakeCrystallon.parse("PUT", "/v1/render", null, ""));
        assertNull(FakeCrystallon.parse("POST", "/v1/render/validate/more", null, ""));
        assertNull(FakeCrystallon.parse("POST", "/nothing/render", null, ""));
        assertEquals("a+b", FakeCrystallon.parse("POST", "/v1/templates/a+b/render", null, "{}").template(), "a plus sign in a path is a plus sign");
        assertEquals("a b", FakeCrystallon.parse("POST", "/v1/templates/a%20b/render", "x=1", "{}").template());
        assertEquals("x=1", FakeCrystallon.parse("POST", "/v1/templates/a%20b/render", "x=1", "{}").query());
        assertEquals("render", FakeCrystallon.parse("POST", "/v1/render", null, "{}").kind());
    }

    @Test
    void theFakePdfIsACopyEachTime() {
        final byte[] a = FakeCrystallon.pdf();
        a[0] = 0;
        assertEquals('%', FakeCrystallon.pdf()[0]);
    }

    @Test
    void closingTheTestServicesTwiceOrOutOfTurnChangesNothing() {
        final GluonifyTestServices first = GluonifyTestServices.start();
        final String url = System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL");
        assertNotNull(url);
        assertEquals(GluonifyTestServices.TOKEN, System.getProperty("GLUONIFY_CRYSTALLON_TOKEN"));
        first.close();
        assertNull(System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
        final GluonifyTestServices second = GluonifyTestServices.start();
        first.close(); // not the active one: nothing happens to the second
        assertNotNull(System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
        assertSame(second.crystallon(), second.crystallon());
        second.close();
        second.close();
        assertNull(System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
    }

    @Test
    void propertiesSetBeforeAreRestoredNotCleared() {
        System.setProperty("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://before.example");
        System.setProperty("GLUONIFY_CRYSTALLON_TOKEN", "before-token");
        try {
            final GluonifyTestServices s = GluonifyTestServices.start();
            assertNotEquals("http://before.example", System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
            s.close();
            assertEquals("http://before.example", System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
            assertEquals("before-token", System.getProperty("GLUONIFY_CRYSTALLON_TOKEN"));
        }
        finally {
            System.clearProperty("GLUONIFY_SERVICE_CRYSTALLON_URL");
            System.clearProperty("GLUONIFY_CRYSTALLON_TOKEN");
        }
    }
}
