package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** The usage contract checker: every deviation a component's response can have, one by one. */
class UsageContractGapsTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String VALID = """
            {"contract":"gluonify-usage/1","component":"gdown","version":"0.7.0","instance":"i-1","node":"n1","at":1760700000000,
             "process":{"cpuSecondsTotal":100.0,"memoryBytes":812646400,"memoryKind":"rss","heapBytes":402653184,"threads":87,"uptimeSeconds":86400},
             "cgroup":{"cpuSecondsTotal":110.0,"memoryBytes":830000000,"memoryLimitBytes":1073741824},
             "attribution":"estimated",
             "byApp":[{"app":"shop","cpuSeconds":40.0,"memoryBytes":52428800,"basis":"estimated"}],
             "unattributedShare":{"cpuSeconds":60.0,"memoryBytes":760000000}}""";

    static ObjectNode valid() {
        return (ObjectNode) JSON.readTree(VALID);
    }

    static List<String> errors(final Consumer<ObjectNode> edit) {
        final ObjectNode n = valid();
        edit.accept(n);
        return UsageContract.check(n);
    }

    static boolean has(final List<String> errors, final String fragment) {
        return errors.stream().anyMatch(e -> e.contains(fragment));
    }

    static void assertRefuses(final Consumer<ObjectNode> edit, final String fragment) {
        final List<String> e = errors(edit);
        assertTrue(has(e, fragment), fragment + " expected in " + e);
    }

    static ObjectNode row(final String app, final double cpu, final String basis) {
        final ObjectNode r = JSON.createObjectNode();
        if (app != null) {
            r.put("app", app);
        }
        r.put("cpuSeconds", cpu);
        r.put("memoryBytes", 1L);
        if (basis != null) {
            r.put("basis", basis);
        }
        return r;
    }

    @Test
    void theReferenceResponseIsCompliant() {
        assertEquals(List.of(), UsageContract.check(valid()));
        assertEquals(List.of(), UsageContract.check(VALID.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void bodiesThatCannotBeChecked() {
        assertEquals(List.of("response absent"), UsageContract.check((byte[]) null));
        assertTrue(UsageContract.check(new byte[UsageContract.MAX_BYTES + 1]).get(0).startsWith("response of "));
        assertEquals(List.of("unreadable JSON"), UsageContract.check("{broken".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("the response is not a JSON object"), UsageContract.check("[1]".getBytes(StandardCharsets.UTF_8)));
        assertEquals(List.of("the response is not a JSON object"), UsageContract.check((JsonNode) null));
    }

    @Test
    void identityTimestampAndProcess() {
        assertRefuses(n -> n.put("component", " "), "component absent");
        assertRefuses(n -> n.put("at", -1), "at : integer >= 0 expected");
        assertRefuses(n -> n.put("at", "yesterday"), "at : integer >= 0 expected");
        assertRefuses(n -> n.put("at", 1.5), "at : integer >= 0 expected");
        assertRefuses(n -> n.remove("process"), "process required");
        assertRefuses(n -> n.put("process", "x"), "process required");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("cpuSecondsTotal", -1), "process.cpuSecondsTotal : number >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("memoryBytes", 1.5), "process.memoryBytes : integer >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("memoryKind", "heap"), "process.memoryKind : rss, pss or cgroup expected");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("heapBytes", "big"), "process.heapBytes : integer >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("threads", -2), "process.threads : integer >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("process")).put("uptimeSeconds", 0.5), "process.uptimeSeconds : integer >= 0 expected");
        assertEquals(List.of(), errors(n -> ((ObjectNode) n.get("process")).putNull("heapBytes")), "an explicit null is as absent");
    }

    @Test
    void theCgroupIsOptionalButMustBeWellFormed() {
        assertEquals(List.of(), errors(n -> n.remove("cgroup")));
        assertEquals(List.of(), errors(n -> n.putNull("cgroup")));
        assertRefuses(n -> n.put("cgroup", "x"), "cgroup : object expected");
        assertRefuses(n -> ((ObjectNode) n.get("cgroup")).put("cpuSecondsTotal", "x"), "cgroup.cpuSecondsTotal : number >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("cgroup")).put("memoryBytes", -1), "cgroup.memoryBytes : integer >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("cgroup")).put("memoryLimitBytes", 1.5), "cgroup.memoryLimitBytes : integer >= 0 expected");
        assertEquals(List.of(), errors(n -> ((ObjectNode) n.get("cgroup")).remove("cpuSecondsTotal")));
    }

    @Test
    void attributionAndTheShapeOfByApp() {
        assertRefuses(n -> n.put("attribution", "guess"), "attribution : none, measured, estimated or prorata expected");
        assertRefuses(n -> n.remove("attribution"), "attribution : none, measured, estimated or prorata expected");
        assertRefuses(n -> n.put("byApp", "x"), "byApp : array expected");
        assertRefuses(n -> n.put("attribution", "none"), "attribution = none with byApp rows");
        assertEquals(List.of(), errors(n -> {
            n.put("attribution", "none");
            n.putArray("byApp");
            n.remove("unattributedShare");
        }));
    }

    @Test
    void rowsAreCheckedOneByOne() {
        assertRefuses(n -> ((ArrayNode) n.get("byApp")).add("not an object"), "byApp : object expected");
        assertRefuses(n -> ((ArrayNode) n.get("byApp")).add(row(null, 0, "estimated")), "byApp : app absent");
        assertRefuses(n -> ((ArrayNode) n.get("byApp")).add(row(" ", 0, "estimated")), "byApp : app absent");
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).put("cpuSeconds", -1), "byApp[shop].cpuSeconds : number >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).put("memoryBytes", "x"), "byApp[shop].memoryBytes : integer >= 0 expected");
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).put("basis", "none"), "byApp[shop].basis : measured, estimated or prorata expected");
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).remove("basis"), "byApp[shop].basis : measured, estimated or prorata expected");
    }

    @Test
    void rowCountsAreCappedAndTheRestRowIsUnique() {
        assertRefuses(n -> {
            final ArrayNode rows = n.putArray("byApp");
            for (int i = 0; i <= UsageReport.MAX_APPS; i++) {
                rows.add(row("app" + i, 0, "measured"));
            }
        }, "rows (maximum " + UsageReport.MAX_APPS);
        assertRefuses(n -> {
            final ArrayNode rows = n.putArray("byApp");
            rows.add(row(UsageReport.OTHER, 20, "estimated"));
            rows.add(row(UsageReport.OTHER, 20, "estimated"));
        }, "a single " + UsageReport.OTHER + " row");
        assertEquals(List.of(), errors(n -> {
            final ArrayNode rows = n.putArray("byApp");
            rows.add(row(UsageReport.OTHER, 40, "estimated"));
        }));
    }

    @Test
    void theUnattributedShareClosesTheSumWithinTolerance() {
        assertRefuses(n -> n.remove("unattributedShare"), "unattributedShare : {cpuSeconds, memoryBytes} expected");
        assertRefuses(n -> n.put("unattributedShare", "x"), "unattributedShare : {cpuSeconds, memoryBytes} expected");
        assertRefuses(n -> ((ObjectNode) n.get("unattributedShare")).put("cpuSeconds", -1), "unattributedShare");
        assertRefuses(n -> ((ObjectNode) n.get("unattributedShare")).put("memoryBytes", 1.5), "unattributedShare");
        assertRefuses(n -> ((ObjectNode) n.get("unattributedShare")).put("cpuSeconds", 70.0), "≠ process.cpuSecondsTotal");
        assertEquals(List.of(), errors(n -> ((ObjectNode) n.get("unattributedShare")).put("cpuSeconds", 60.005)), "within the rounding tolerance");
        assertEquals(List.of(), errors(n -> ((ObjectNode) n.get("process")).put("cpuSecondsTotal", 100.0)));
    }

    @Test
    void storageIsOptionalIntegersWithRowsNeverAboveTheTotal() {
        assertEquals(List.of(), errors(n -> n.put("storageBytes", 1000)));
        assertRefuses(n -> n.put("storageBytes", -1), "storageBytes: integer >= 0 expected");
        assertRefuses(n -> n.put("storageBytes", "lots"), "storageBytes: integer >= 0 expected");
        assertEquals(List.of(), errors(n -> n.putNull("storageBytes")));
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).put("storageBytes", "x"), "byApp[shop].storageBytes: integer >= 0 expected");
        assertEquals(List.of(), errors(n -> ((ObjectNode) n.get("byApp").get(0)).putNull("storageBytes")), "a null row value is skipped");
        assertRefuses(n -> ((ObjectNode) n.get("byApp").get(0)).put("storageBytes", 10), "storageBytes: total required as soon as a byApp row carries storageBytes");
        assertRefuses(n -> {
            n.put("storageBytes", 5);
            ((ObjectNode) n.get("byApp").get(0)).put("storageBytes", 10);
        }, "sum of byApp.storageBytes > storageBytes");
        assertEquals(List.of(), errors(n -> {
            n.put("storageBytes", 10);
            ((ObjectNode) n.get("byApp").get(0)).put("storageBytes", 10);
        }));
    }
}
