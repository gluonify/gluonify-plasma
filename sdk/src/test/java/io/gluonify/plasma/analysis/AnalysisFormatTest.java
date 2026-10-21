package io.gluonify.plasma.analysis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Common format of the findings (analysis.md § 3.6): JSON read back identically, stable identifiers, validation, native reflection. */
class AnalysisFormatTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void findingRoundTripsAndUsesLowerCaseNames() throws Exception {
        Finding f = Finding.open(FindingKind.SONAR, "acme.shop", "GN04", Severity.MAJOR, false, new Location("src/main/java/acme/OrderClient.java", 42), Map.of("type", "acme.Order"))
                .withRef("gluonify:GN04");
        String s = json.writeValueAsString(f);
        JsonNode n = json.readTree(s);
        assertEquals(1, n.get("v").asInt());
        assertEquals("sonar", n.get("kind").asString());
        assertEquals("major", n.get("severity").asString());
        assertEquals("open", n.get("status").asString());
        assertEquals(42, n.get("location").get("line").asInt());
        assertEquals("acme.Order", n.get("args").get("type").asString());
        assertEquals(64, n.get("id").asString().length());
        assertEquals(f, json.readValue(s, Finding.class));
    }

    @Test
    void specExampleIsRead() throws Exception {
        Finding f = json.readValue("""
                {"v":1,"id":"abc","kind":"deps","rule":"GN04","severity":"critical","blocking":true,
                 "location":{"file":"pom.xml"},"args":{"type":"acme.Order"},"ref":"GHSA-xxxx-yyyy-zzzz","status":"accepted","later":1}""", Finding.class);
        assertEquals(FindingKind.DEPS, f.kind());
        assertEquals(Severity.CRITICAL, f.severity());
        assertEquals(FindingStatus.ACCEPTED, f.status());
        assertTrue(f.blocking());
        assertEquals("GHSA-xxxx-yyyy-zzzz", f.ref());
    }

    @Test
    void nullFieldsAreOmittedAndDefaultsApplied() throws Exception {
        Finding f = json.readValue("{\"kind\":\"native\",\"rule\":\"N-ELF\",\"severity\":\"blocker\"}", Finding.class);
        assertEquals(Finding.CURRENT_VERSION, f.v());
        assertEquals(Map.of(), f.args());
        assertEquals(FindingStatus.OPEN, f.status());
        String s = json.writeValueAsString(Finding.open(FindingKind.NATIVE, "b1", "N-ELF", Severity.BLOCKER, true, null, null));
        assertFalse(s.contains("location"));
        assertFalse(s.contains("ref"));
        assertTrue(s.contains("\"args\":{}"));
    }

    @Test
    void idIsStableAndDependsOnEveryPart() throws Exception {
        Location l = new Location("a/B.java", 3);
        String id = Finding.idOf(FindingKind.CONFORMITY, "shop", "R-SECRET", l);
        assertEquals(id, Finding.idOf(FindingKind.CONFORMITY, "shop", "R-SECRET", new Location("a/B.java", 3)));
        assertEquals(id, Finding.open(FindingKind.CONFORMITY, "shop", "R-SECRET", Severity.BLOCKER, true, l, Map.of()).id());
        Set<String> ids = new HashSet<>(List.of(id,
                Finding.idOf(FindingKind.NATIVE, "shop", "R-SECRET", l),
                Finding.idOf(FindingKind.CONFORMITY, "other", "R-SECRET", l),
                Finding.idOf(FindingKind.CONFORMITY, "shop", "R-ECOUTE", l),
                Finding.idOf(FindingKind.CONFORMITY, "shop", "R-SECRET", new Location("a/B.java", 4)),
                Finding.idOf(FindingKind.CONFORMITY, "shop", "R-SECRET", null)));
        assertEquals(6, ids.size());
        assertEquals(sha256Hex("conformity|shop|R-SECRET|a/B.java:3"), id);
    }

    @Test
    void ruleCodesAndArgsAreValidated() {
        for (String ok : List.of("N-ELF", "N-METADONNEES-FICHIER", "R-FICHIER-SENSIBLE", "GN04", "SEO-H1-IN-TEXT", "gluonify:GN01", "java:S2095", "API-ASSERTION")) {
            Finding.open(new Finding.Subject(FindingKind.SONAR, null, ok), Severity.INFO, false, null, null);
        }
        for (String bad : List.of("", "n-elf", "N ELF", "N-", "-N", "Le binaire n'est pas un ELF", "N-ELF\n")) {
            assertThrows(IllegalArgumentException.class, () -> Finding.open(new Finding.Subject(FindingKind.NATIVE, null, bad), Severity.INFO, false, null, null), bad);
        }
        final var input = Map.of("a b", "x");
        assertThrows(IllegalArgumentException.class, () -> Finding.open(new Finding.Subject(FindingKind.NATIVE, null, "N-ELF"), Severity.INFO, false, null, input));
        assertThrows(NullPointerException.class, () -> Finding.open(new Finding.Subject(FindingKind.NATIVE, null, "N-ELF"), null, false, null, null));
        Map<String, String> big = new LinkedHashMap<>();
        big.put("file", "x".repeat(5000));
        assertEquals(Finding.MAX_ARG_LENGTH, Finding.open(new Finding.Subject(FindingKind.NATIVE, null, "N-ELF"), Severity.INFO, false, null, big).args().get("file").length());
        final var subject = Finding.open(new Finding.Subject(FindingKind.NATIVE, null, "N-ELF"), Severity.INFO, false, null, Map.of("a", "b")).args();
        assertThrows(UnsupportedOperationException.class, () -> subject.put("c", "d"));
    }

    @Test
    void locationIsAlwaysRelativeToTheRepository() {
        for (String bad : List.of("/work/abc/src/A.java", "../etc/passwd", "a/../../b", "C:\\x", "a\\b", " ")) {
            assertThrows(IllegalArgumentException.class, () -> Location.of(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> new Location("a", 0));
        assertEquals("pom.xml", Location.of("pom.xml").key());
        assertEquals("a/..b/c:7", new Location("a/..b/c", 7).key());
    }

    @Test
    void severityAndStatusOrdering() {
        assertTrue(Severity.BLOCKER.atLeast(Severity.CRITICAL));
        assertTrue(Severity.MAJOR.atLeast(Severity.MAJOR));
        assertFalse(Severity.MINOR.atLeast(Severity.MAJOR));
        assertEquals(Severity.CRITICAL, Severity.parse(" Critical "));
        assertThrows(IllegalArgumentException.class, () -> Severity.parse("high"));
        assertEquals(AnalysisStatus.BLOCKED, AnalysisStatus.worst(AnalysisStatus.OK, AnalysisStatus.BLOCKED, AnalysisStatus.WARNING));
        assertEquals(AnalysisStatus.WARNING, AnalysisStatus.worst(AnalysisStatus.UNAVAILABLE, AnalysisStatus.WARNING));
        assertEquals(AnalysisStatus.OK, AnalysisStatus.worst(AnalysisStatus.SKIPPED, AnalysisStatus.OK, null));
        assertEquals(AnalysisStatus.SKIPPED, AnalysisStatus.worst());
    }

    @Test
    void summaryMatchesTheSpecShape() throws Exception {
        AnalysisSummary.NativeSummary n = new AnalysisSummary.NativeSummary(AnalysisStatus.OK, null, "arm64", 61234567L, 0.03, "2.34", "25.0.1", 412, 37,
                new AnalysisSummary.Startup(0.21, 0.34, 2));
        AnalysisSummary s = new AnalysisSummary(null, n, null, new AnalysisSummary.SonarSummary(AnalysisStatus.SKIPPED, "no-organization", null, null, null, null), 9);
        assertEquals(AnalysisStatus.OK, s.status());
        JsonNode t = json.readTree(json.writeValueAsString(s));
        assertEquals("ok", t.get("status").asString());
        assertEquals("arm64", t.get("native").get("arch").asString());
        assertEquals(0.21, t.get("native").get("startup").get("medianSeconds").asDouble());
        assertEquals("skipped", t.get("sonar").get("status").asString());
        assertFalse(t.has("deps"));
        assertFalse(t.has("nativeImage"));
        assertEquals(s, json.readValue(json.writeValueAsString(s), AnalysisSummary.class));
        AnalysisSummary deps = s.withNative(n.withStartup(n.startup(), AnalysisStatus.WARNING));
        assertEquals(AnalysisStatus.WARNING, deps.status());
        AnalysisSummary d = json.readValue("""
                {"status":"warning","deps":{"status":"warning","components":183,"db":{"date":"2026-10-11"},"counts":{"critical":0,"high":2}},"findings":2}""", AnalysisSummary.class);
        assertEquals(2, d.deps().counts().get("high"));
        assertEquals("2026-10-11", d.deps().db().date());
    }

    @Test
    void reportSortsMostSevereFirstAndCountsFindings() throws Exception {
        Finding minor = Finding.open(FindingKind.NATIVE, "b", "N-TAILLE", Severity.MINOR, false, null, Map.of("sizeMb", "250"));
        Finding blocker = Finding.open(FindingKind.NATIVE, "b", "N-ELF", Severity.BLOCKER, true, null, Map.of());
        Finding conf = Finding.open(FindingKind.CONFORMITY, "b", "R-SANTE", Severity.MAJOR, false, Location.of("pom.xml"), Map.of());
        AnalysisReport r = new AnalysisReport(0, "b", new AnalysisSummary(null, null, null, null, 0), List.of(minor, conf, blocker));
        assertEquals(List.of(blocker, conf, minor), r.findings());
        assertEquals(3, r.summary().findings());
        assertTrue(r.blocked());
        assertFalse(new AnalysisReport(1, null, null, List.of(blocker.withStatus(FindingStatus.ACCEPTED), minor)).blocked());
        assertEquals(r, json.readValue(json.writeValueAsString(r), AnalysisReport.class));
        assertEquals(List.of(), new AnalysisReport(1, null, null, null).findings());
    }

    @Test
    void unitsFollowTheCatalogueNaming() {
        final Pattern naming = Pattern.compile("(bup|geiger)\\.[a-z]+\\.[a-z]+");
        for (String u : AnalysisUnits.ALL) assertTrue(naming.matcher(u).matches(), u);
        assertEquals(new HashSet<>(AnalysisUnits.ALL).size(), AnalysisUnits.ALL.size());
        assertEquals(List.of("native", "deps", "sonar"), AnalysisUnits.ANALYSIS_KINDS);
    }

    @Test
    void everyTypeOfThePackageIsDeclaredForNativeReflection() throws Exception {
        JsonNode meta;
        try (InputStream in = getClass().getResourceAsStream("/META-INF/native-image/io.gluonify.plasma/gluonify-plasma-sdk/reachability-metadata.json")) {
            meta = json.readTree(in);
        }
        Set<String> declared = new HashSet<>();
        meta.get("reflection").forEach(e -> declared.add(e.get("type").asString()));
        List<Class<?>> types = List.of(Finding.class, Location.class, Severity.class, FindingKind.class, FindingStatus.class, AnalysisStatus.class, AnalysisSummary.class,
                AnalysisSummary.NativeSummary.class, AnalysisSummary.Startup.class, AnalysisSummary.DepsSummary.class, AnalysisSummary.Db.class,
                AnalysisSummary.SonarSummary.class, AnalysisReport.class);
        for (Class<?> c : types) assertTrue(declared.contains(c.getName()), c.getName());
        for (Class<?> c : AnalysisSummary.class.getDeclaredClasses()) {
            if (Modifier.isPublic(c.getModifiers())) assertTrue(declared.contains(c.getName()), c.getName());
        }
        assertNotEquals(0, declared.size());
    }

    private static String sha256Hex(String s) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }
}
