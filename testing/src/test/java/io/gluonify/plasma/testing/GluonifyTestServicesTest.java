package io.gluonify.plasma.testing;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.crystallon.Check;
import io.gluonify.plasma.crystallon.client.CrystallonClient;
import io.gluonify.plasma.crystallon.client.CrystallonException;
import io.gluonify.plasma.crystallon.client.CrystallonTokens;
import io.gluonify.plasma.crystallon.client.RenderOptions;
import io.gluonify.plasma.crystallon.client.RenderedPdf;
import io.gluonify.plasma.services.Endpoint;
import io.gluonify.plasma.services.GluonifyServices;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GluonifyTestServicesTest {

    @Test
    void crystallonSansConfiguration() {
        assertNull(System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"));
        try (GluonifyTestServices services = GluonifyTestServices.start()) {
            final Endpoint e = GluonifyServices.endpoint("crystallon");
            assertEquals(Endpoint.Source.OVERRIDE, e.source());
            final CrystallonClient client = CrystallonClient.create();
            final RenderedPdf pdf = client.byName("Gluonify invoice").render(Map.of("total", 12));
            assertEquals(1, pdf.pages());
            assertArrayEquals(FakeCrystallon.pdf(), pdf.bytes());
            final FakeCrystallon.Render r = services.crystallon().lastRender();
            assertEquals("render", r.kind());
            assertEquals("Gluonify invoice", r.template());
            assertTrue(r.byName());
            assertTrue(r.body().contains("\"total\":12"), r.body());

            client.byId("t-1").version(3).render(Map.of(), RenderOptions.builder().pdfa(true).build());
            assertEquals("t-1", services.crystallon().lastRender().template());
            assertTrue(services.crystallon().lastRender().query().contains("version=3"));

            client.inline("quote").render(Map.of());
            assertNull(services.crystallon().lastRender().template());
            assertTrue(services.crystallon().lastRender().body().contains("\"template\":\"quote\""));

            final Check check = client.byName("x").validate(Map.of());
            assertTrue(check.valid());
            assertEquals(4, services.crystallon().renders().size());

            services.crystallon().failNext(422, "CRYSTALLON-0015", "Invalid template");
            final var input2 = Map.of();
            final var subject2 = client.byName("x");
            final CrystallonException ex = assertThrows(CrystallonException.class, () -> subject2.render(input2));
            assertEquals(422, ex.status());
            assertEquals("CRYSTALLON-0015", ex.code());
            client.byName("x").render(Map.of()); // the failure applies only once

            final CrystallonClient intruder = CrystallonClient.builder().baseUrl(e.url()).tokens(CrystallonTokens.fixed("other")).build();
            final var input = Map.of();
            final var subject = intruder.byName("x");
            assertEquals(401, assertThrows(CrystallonException.class, () -> subject.render(input)).status());

            assertThrows(IllegalStateException.class, GluonifyTestServices::start, "only one instance at a time");
        }
        assertNull(System.getProperty("GLUONIFY_SERVICE_CRYSTALLON_URL"), "properties restored");
        assertNull(System.getProperty("GLUONIFY_CRYSTALLON_TOKEN"));
        GluonifyTestServices.start().close(); // restartable
    }

    @Test
    void pdfMinimalValide() {
        final String pdf = new String(FakeCrystallon.pdf(), StandardCharsets.ISO_8859_1);
        assertTrue(pdf.startsWith("%PDF-1.4"));
        assertTrue(pdf.endsWith("%%EOF\n"));
        final int startxref = Integer.parseInt(pdf.substring(pdf.lastIndexOf("startxref\n") + 10, pdf.lastIndexOf("\n%%EOF")).strip());
        assertTrue(pdf.startsWith("xref", startxref), "startxref points to the table");
        for (int k = 1; k <= 3; k++) {
            final String entry = pdf.substring(startxref).split("\n")[2 + k];
            final int offset = Integer.parseInt(entry.substring(0, 10));
            assertTrue(pdf.startsWith(k + " 0 obj", offset), "exact offset of object " + k);
        }
    }

    @Test
    void routes() {
        assertEquals("a b", FakeCrystallon.parse("POST", "/p/v1/templates/by-name/a%20b/render", null, "{}").template());
        assertEquals("validate", FakeCrystallon.parse("POST", "/v1/render/validate", null, "{}").kind());
        assertNull(FakeCrystallon.parse("GET", "/v1/render", null, ""));
        assertNull(FakeCrystallon.parse("POST", "/v1/templates/a/b/render", null, ""));
    }
}
