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
        assertTrue(check(VALID.replaceAll("(?s),\\s*\"unattributedShare\":\\{[^}]*\\}", "")).stream().anyMatch(e -> e.startsWith("unattributedShare")));
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
        final String ok = VALID.replace("\"attribution\":\"estimated\"", "\"attribution\":\"none\"").replaceAll("(?s)\"byApp\":\\[.*\\],\\s*\"unattributedShare\":\\{[^}]*\\}", "\"byApp\":[]");
        assertEquals(List.of(), check(ok));
    }

    @Test
    void sommeDesPartsEgaleAuTotalALArrondiPres() {
        assertTrue(check(VALID.replace("1200.0", "1199.995")).isEmpty());
        assertTrue(check(VALID.replace("1200.0", "900.0")).contains("Σ byApp.cpuSeconds + unattributedShare.cpuSeconds ≠ process.cpuSecondsTotal"));
    }

    @Test
    void plusDeCinqCentsLignesRefuseSaufOther() {
        final StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            rows.append(i == 0 ? "" : ",").append("{\"app\":\"a").append(i).append("\",\"cpuSeconds\":0,\"memoryBytes\":1,\"basis\":\"estimated\"}");
        }
        final String many = VALID.replaceAll("(?s)\"byApp\":\\[.*\\],\\s*\"unattributedShare\"", "\"byApp\":[" + rows + "],\"unattributedShare\"").replace("1200.0", "1234.5");
        assertTrue(check(many).stream().anyMatch(e -> e.contains("501 lignes")));
        final String other = many.replace("\"a500\"", "\"_other\"");
        assertEquals(List.of(), check(other));
        assertTrue(check(other.replace("\"a499\"", "\"_other\"")).contains("byApp : une seule ligne _other"));
    }

    @Test
    void ligneSansIdentiteOuSansBasis() {
        assertTrue(check(VALID.replace("\"app\":\"shop\",", "")).contains("byApp : app absent"));
        assertTrue(check(VALID.replace("\"basis\":\"estimated\"", "\"basis\":\"?\"")).stream().anyMatch(e -> e.contains(".basis")));
    }

    @Test
    void tailleBorneeEtJsonIllisible() {
        assertEquals(List.of(), UsageContract.check(VALID.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertTrue(UsageContract.check(new byte[UsageContract.MAX_BYTES + 1]).get(0).contains("octets"));
        assertEquals(List.of("JSON illisible"), UsageContract.check("{pas du json".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(List.of("réponse absente"), UsageContract.check((byte[]) null));
    }
}
