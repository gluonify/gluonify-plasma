package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class UsageContractTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String VALID = """
            {"contract":"gluonify-usage/1","component":"gdown","version":"0.7.0","instance":"i-1","node":"n1","at":1760700000000,
             "process":{"cpuSecondsTotal":1234.5,"memoryBytes":812646400,"memoryKind":"rss","heapBytes":402653184,"threads":87,"uptimeSeconds":86400},
             "cgroup":{"cpuSecondsTotal":1240.1,"memoryBytes":830000000,"memoryLimitBytes":1073741824},
             "attribution":"estimated",
             "byApp":[{"app":"shop","uuid":"u","cpuSeconds":34.5,"memoryBytes":52428800,"basis":"estimated","unit":"db app1"}],
             "unattributedShare":{"cpuSeconds":1200.0,"memoryBytes":760000000},"futur":{"champ":"inconnu"}}""";

    private static List<String> check(final String json) {
        return UsageContract.check(JSON.readTree(json));
    }

    /** {@link #VALID} avec un autre tableau {@code byApp} et un autre {@code unattributedShare} (découpage par indices : aucune expression régulière). */
    private static String withByApp(final String byAppArray, final String unattributedShare) {
        final int from = VALID.indexOf("\"byApp\"");
        final int to = VALID.indexOf("\"futur\"");
        return VALID.substring(0, from) + "\"byApp\":" + byAppArray + "," + (unattributedShare == null ? "" : "\"unattributedShare\":" + unattributedShare + ",")
                + VALID.substring(to);
    }

    @Test
    void exempleDuPlanEstValideEtLesChampsInconnusSontToleres() {
        assertEquals(List.of(), check(VALID));
    }

    @Test
    void champManquant() {
        assertTrue(check(VALID.replace("\"contract\":\"gluonify-usage/1\",", "")).stream().anyMatch(e -> e.startsWith("contract")));
        assertTrue(check(VALID.replace("\"component\":\"gdown\",", "")).contains("component absent"));
        assertTrue(check(VALID.replaceAll("\"process\":\\{[^}]*\\},", "")).contains("process obligatoire"));
        assertTrue(check(VALID.replace("\"memoryKind\":\"rss\",", "")).stream().anyMatch(e -> e.startsWith("process.memoryKind")));
        assertTrue(check(VALID.replace("\"byApp\"", "\"x\"")).stream().anyMatch(e -> e.startsWith("byApp")));
        assertTrue(check(VALID.replace("\"attribution\":\"estimated\"", "\"attribution\":\"guess\"")).stream().anyMatch(e -> e.startsWith("attribution")));
        assertTrue(check(withByApp(VALID.substring(VALID.indexOf("[{\"app\""), VALID.indexOf("}]") + 2), null)).stream()
                .anyMatch(e -> e.startsWith("unattributedShare")));
    }

    @Test
    void cpuNegatifOuMauvaisType() {
        assertTrue(check(VALID.replace("1234.5", "-1")).stream().anyMatch(e -> e.startsWith("process.cpuSecondsTotal")));
        assertTrue(check(VALID.replace("812646400", "\"beaucoup\"")).stream().anyMatch(e -> e.startsWith("process.memoryBytes")));
        assertTrue(check("[]").get(0).contains("objet"));
    }

    @Test
    void noneAvecPartsRefuse() {
        assertTrue(check(VALID.replace("\"attribution\":\"estimated\"", "\"attribution\":\"none\"")).contains("attribution = none avec des lignes byApp"));
        final String ok = withByApp("[]", null).replace("\"attribution\":\"estimated\"", "\"attribution\":\"none\"");
        assertEquals(List.of(), check(ok));
    }

    @Test
    void sommeDesPartsEgaleAuTotalALArrondiPres() {
        assertTrue(check(VALID.replace("1200.0", "1199.995")).isEmpty());
        assertTrue(check(VALID.replace("1200.0", "900.0")).contains("Σ byApp.cpuSeconds + unattributedShare.cpuSeconds ≠ process.cpuSecondsTotal"));
    }

    /** Storage (additive, 2026-10-17): optional; integers >= 0; rows need the total and never exceed it; storage alone with {@code none} is valid. */
    @Test
    void storageIsOptionalAndConsistent() {
        final String row = "{\"app\":\"shop\",\"uuid\":\"u\",\"cpuSeconds\":34.5,\"memoryBytes\":52428800,\"basis\":\"prorata\"";
        final String rest = "{\"cpuSeconds\":1200.0,\"memoryBytes\":760000000}";
        final String withRow = withByApp("[" + row + ",\"storageBytes\":700}]", rest);
        assertEquals(List.of(), check(withRow.replace("\"at\":", "\"storageBytes\":1000,\"at\":")));
        assertEquals(List.of(), check(withRow.replace("\"at\":", "\"storageBytes\":700,\"at\":")), "rows' sum equal to the total");
        assertTrue(check(withRow.replace("\"at\":", "\"storageBytes\":699,\"at\":")).contains("sum of byApp.storageBytes > storageBytes"));
        assertTrue(check(withRow).contains("storageBytes: total required as soon as a byApp row carries storageBytes"));
        assertTrue(check(withRow.replace("\"storageBytes\":700", "\"storageBytes\":-1").replace("\"at\":", "\"storageBytes\":1000,\"at\":")).stream()
                .anyMatch(e -> e.startsWith("byApp[shop].storageBytes")));
        assertTrue(check(VALID.replace("\"at\":", "\"storageBytes\":1.5,\"at\":")).contains("storageBytes: integer >= 0 expected"));
        assertTrue(check(VALID.replace("\"at\":", "\"storageBytes\":\"lots\",\"at\":")).contains("storageBytes: integer >= 0 expected"));
        assertEquals(List.of(), check(VALID.replace("\"at\":", "\"storageBytes\":null,\"at\":")), "null is absent");
        final String none = withByApp("[]", null).replace("\"attribution\":\"estimated\"", "\"attribution\":\"none\"")
                .replace("\"at\":", "\"storageBytes\":123456,\"at\":");
        assertEquals(List.of(), check(none), "storage without any row (window without I/O)");
    }

    @Test
    void plusDeCinqCentsLignesRefuseSaufOther() {
        final StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            rows.append(i == 0 ? "" : ",").append("{\"app\":\"a").append(i).append("\",\"cpuSeconds\":0,\"memoryBytes\":1,\"basis\":\"estimated\"}");
        }
        final String many = withByApp("[" + rows + "]", "{\"cpuSeconds\":1234.5,\"memoryBytes\":760000000}");
        assertTrue(check(many).stream().anyMatch(e -> e.contains("501 lignes")));
        final String other = many.replace("\"a500\"", "\"_other\"");
        assertEquals(List.of(), check(other));
        assertTrue(check(other.replace("\"a499\"", "\"_other\"")).contains("byApp : une seule ligne _other"));
    }

    @Test
    void ligneSansIdentiteOuSansBasis() {
        assertTrue(check(VALID.replace("\"app\":\"shop\",", "")).contains("byApp : app absent"));
        assertTrue(check(VALID.replace("\"basis\":\"estimated\"", "\"basis\":\"?\"")).stream().anyMatch(e -> e.contains(".basis")));
        // « none » est une attribution du composant, jamais le basis d'une ligne : une ligne est toujours attribuée d'une façon
        assertTrue(check(VALID.replace("\"basis\":\"estimated\"", "\"basis\":\"none\"")).contains("byApp[shop].basis : measured, estimated ou prorata attendu"));
        for (final String basis : List.of("measured", "prorata")) {
            assertEquals(List.of(), check(VALID.replace("\"basis\":\"estimated\"", "\"basis\":\"" + basis + "\"")));
        }
    }

    @Test
    void tailleBorneeEtJsonIllisible() {
        assertEquals(List.of(), UsageContract.check(VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(UsageContract.check(new byte[UsageContract.MAX_BYTES + 1]).get(0).contains("octets"));
        assertEquals(List.of("JSON illisible"), UsageContract.check("{pas du json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(List.of("réponse absente"), UsageContract.check((byte[]) null));
    }
}
