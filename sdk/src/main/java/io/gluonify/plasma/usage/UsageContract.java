package io.gluonify.plasma.usage;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Validator of the usage contract {@code gluonify-usage/1} ({@code usage-mcp.md} § 3.2): used by each component's tests and by
 * {@code check-usage-contract.sh}. Returns the list of deviations (empty = compliant), writes nothing. An unknown field is tolerated (additive contract).
 * Rules: {@code process} required; {@code attribution} known; {@code none} with no {@code byApp} row; each row identified with a
 * {@code basis} among {@code measured}, {@code estimated}, {@code prorata} (never {@code none}: a row is always attributed in some way); and
 * sum of the CPU of {@code byApp} + {@code unattributedShare} = {@code process.cpuSecondsTotal} up to rounding.
 * <p><b>Cap</b>: at most {@link UsageReport#MAX_APPS} (500) rows of real applications (the largest, by memory then CPU), <b>plus a single</b>
 * {@link UsageReport#OTHER} row ({@code _other}) that groups the rest; {@code _other} does not count in the 500. Same reading for all repositories.
 * <p><b>Storage</b> (additive, 2026-10-17): {@code storageBytes} is optional at the top level (bytes stored by the component) and on each row; when present it is an
 * integer ≥ 0, and as soon as a row carries one the top-level total is required and the rows' sum is at most that total. A report without it stays valid.
 * Does not detect a secret: component tests look for their configuration values ({@code UsageContractTests.secrets()}).
 */
public final class UsageContract {
    /** Maximum size of a response. */
    public static final int MAX_BYTES = 256 * 1024;

    private static final Set<String> ATTRIBUTIONS = Set.of(UsageReport.NONE, UsageReport.MEASURED, UsageReport.ESTIMATED, UsageReport.PRORATA);
    /** {@code basis} of a {@code byApp} row: never {@code none}. */
    private static final Set<String> BASES = Set.of(UsageReport.MEASURED, UsageReport.ESTIMATED, UsageReport.PRORATA);
    private static final Set<String> MEMORY_KINDS = Set.of("rss", "pss", "cgroup");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String CPU_SECONDS_TOTAL = "cpuSecondsTotal";
    private static final String CPU_SECONDS = "cpuSeconds";
    private static final String MEMORY_BYTES = "memoryBytes";
    private static final String PROCESS_PREFIX = "process.";
    private static final String CGROUP_PREFIX = "cgroup.";
    private static final String BY_APP_PREFIX = "byApp[";
    private static final String NUMBER_EXPECTED = " : number >= 0 expected";
    private static final String STORAGE_BYTES = "storageBytes";
    private static final String INTEGER_EXPECTED = ": integer >= 0 expected";

    private UsageContract() {}

    /** Validates a response body: bounded size, valid JSON, then {@link #check(JsonNode)}. */
    public static List<String> check(final byte[] body) {
        if (body == null) {
            return List.of("response absent");
        }
        if (body.length > MAX_BYTES) {
            return List.of("response of " + body.length + " bytes (maximum " + MAX_BYTES + ")");
        }
        try {
            return check(MAPPER.readTree(new String(body, StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            return List.of("unreadable JSON");
        }
    }

    /** The deviations from the contract of an already-read response; empty if it is compliant. */
    public static List<String> check(final JsonNode root) {
        if (root == null || !root.isObject()) {
            return List.of("the response is not a JSON object");
        }
        final List<String> errors = new ArrayList<>();
        checkIdentity(root, errors);
        final Double cpuTotal = checkProcess(root.path("process"), errors);
        checkCgroup(root.get("cgroup"), errors);
        final String attribution = text(root, "attribution");
        if (!known(ATTRIBUTIONS, attribution)) {
            errors.add("attribution : none, measured, estimated or prorata expected");
        }
        final JsonNode byApp = root.path("byApp");
        if (!byApp.isArray()) {
            errors.add("byApp : array expected (empty if attribution = none)");
            return errors;
        }
        if (UsageReport.NONE.equals(attribution) && !byApp.isEmpty()) {
            errors.add("attribution = none with byApp rows");
        }
        final double sum = checkRows(byApp, errors);
        if (known(ATTRIBUTIONS, attribution) && !UsageReport.NONE.equals(attribution)) {
            checkUnattributed(root.path("unattributedShare"), sum, cpuTotal, byApp.size(), errors);
        }
        checkStorage(root, byApp, errors);
        return errors;
    }

    /** {@code storageBytes}: optional integer ≥ 0 (total and rows); rows that carry storage need the total, and their sum is at most the total. */
    private static void checkStorage(final JsonNode root, final JsonNode byApp, final List<String> errors) {
        final JsonNode total = root.get(STORAGE_BYTES);
        if (total != null && !total.isNull() && !isInteger(root, STORAGE_BYTES)) {
            errors.add(STORAGE_BYTES + INTEGER_EXPECTED);
        }
        long rows = 0;
        boolean any = false;
        for (final JsonNode row : byApp) {
            final JsonNode v = row.isObject() ? row.get(STORAGE_BYTES) : null;
            if (v == null || v.isNull()) {
                continue;
            }
            if (!isInteger(row, STORAGE_BYTES)) {
                errors.add(BY_APP_PREFIX + text(row, "app") + "]." + STORAGE_BYTES + INTEGER_EXPECTED);
                continue;
            }
            any = true;
            rows += v.asLong();
        }
        if (!any) {
            return;
        }
        if (!isInteger(root, STORAGE_BYTES)) {
            errors.add("storageBytes: total required as soon as a byApp row carries storageBytes");
        } else if (rows > root.get(STORAGE_BYTES).asLong()) {
            errors.add("sum of byApp.storageBytes > storageBytes");
        }
    }

    /** {@code contract}, {@code component}, {@code at}. */
    private static void checkIdentity(final JsonNode root, final List<String> errors) {
        if (!UsageReport.CONTRACT_ID.equals(text(root, "contract"))) {
            errors.add("contract : " + UsageReport.CONTRACT_ID + " expected");
        }
        final String component = text(root, "component");
        if (component == null || component.isBlank()) {
            errors.add("component absent");
        }
        if (!root.path("at").isIntegralNumber() || root.path("at").asLong() < 0) {
            errors.add("at : integer >= 0 expected");
        }
    }

    /** {@code process} required; returns {@code cpuSecondsTotal} if it is readable. */
    private static Double checkProcess(final JsonNode process, final List<String> errors) {
        if (!process.isObject()) {
            errors.add("process required");
            return null;
        }
        Double cpuTotal = null;
        if (!isNumber(process, CPU_SECONDS_TOTAL)) {
            errors.add(PROCESS_PREFIX + CPU_SECONDS_TOTAL + NUMBER_EXPECTED);
        } else {
            cpuTotal = process.get(CPU_SECONDS_TOTAL).asDouble();
        }
        if (!isInteger(process, MEMORY_BYTES)) {
            errors.add(PROCESS_PREFIX + MEMORY_BYTES + " : integer >= 0 expected");
        }
        if (!known(MEMORY_KINDS, text(process, "memoryKind"))) {
            errors.add(PROCESS_PREFIX + "memoryKind : rss, pss or cgroup expected");
        }
        optionalInteger(process, "heapBytes", PROCESS_PREFIX, errors);
        optionalInteger(process, "threads", PROCESS_PREFIX, errors);
        optionalInteger(process, "uptimeSeconds", PROCESS_PREFIX, errors);
        return cpuTotal;
    }

    /** {@code cgroup} optional; if present, an object with numeric fields. */
    private static void checkCgroup(final JsonNode cgroup, final List<String> errors) {
        if (cgroup == null || cgroup.isNull()) {
            return;
        }
        if (!cgroup.isObject()) {
            errors.add("cgroup : object expected");
            return;
        }
        if (cgroup.has(CPU_SECONDS_TOTAL) && !isNumber(cgroup, CPU_SECONDS_TOTAL)) {
            errors.add(CGROUP_PREFIX + CPU_SECONDS_TOTAL + NUMBER_EXPECTED);
        }
        optionalInteger(cgroup, MEMORY_BYTES, CGROUP_PREFIX, errors);
        optionalInteger(cgroup, "memoryLimitBytes", CGROUP_PREFIX, errors);
    }

    /** Each {@code byApp} row, the cap and the uniqueness of {@code _other}; returns the sum of the rows' CPU. */
    private static double checkRows(final JsonNode byApp, final List<String> errors) {
        int apps = 0;
        int others = 0;
        double cpu = 0;
        for (final JsonNode row : byApp) {
            if (!row.isObject()) {
                errors.add("byApp : object expected");
                continue;
            }
            final String app = text(row, "app");
            if (app == null || app.isBlank()) {
                errors.add("byApp : app absent");
            } else if (UsageReport.OTHER.equals(app)) {
                others++;
            } else {
                apps++;
            }
            cpu += checkRow(row, app, errors);
        }
        if (apps > UsageReport.MAX_APPS) {
            errors.add("byApp : " + apps + " rows (maximum " + UsageReport.MAX_APPS + ", then one " + UsageReport.OTHER + " row)");
        }
        if (others > 1) {
            errors.add("byApp : a single " + UsageReport.OTHER + " row");
        }
        return cpu;
    }

    /** The fields of a row; returns its CPU (0 if unreadable). */
    private static double checkRow(final JsonNode row, final String app, final List<String> errors) {
        double cpu = 0;
        if (!isNumber(row, CPU_SECONDS)) {
            errors.add(BY_APP_PREFIX + app + "]." + CPU_SECONDS + NUMBER_EXPECTED);
        } else {
            cpu = row.get(CPU_SECONDS).asDouble();
        }
        if (!isInteger(row, MEMORY_BYTES)) {
            errors.add(BY_APP_PREFIX + app + "]." + MEMORY_BYTES + " : integer >= 0 expected");
        }
        if (!known(BASES, text(row, "basis"))) {
            errors.add(BY_APP_PREFIX + app + "].basis : measured, estimated or prorata expected");
        }
        return cpu;
    }

    /** {@code unattributedShare} required as soon as there is an attribution, and sum of shares + rest = total up to rounding. */
    private static void checkUnattributed(final JsonNode rest, final double sum, final Double cpuTotal, final int lines, final List<String> errors) {
        if (!rest.isObject() || !isNumber(rest, CPU_SECONDS) || !isInteger(rest, MEMORY_BYTES)) {
            errors.add("unattributedShare : {cpuSeconds, memoryBytes} expected");
            return;
        }
        if (cpuTotal != null) {
            final double tolerance = 0.01 + 0.001 * lines;
            if (Math.abs(sum + rest.get(CPU_SECONDS).asDouble() - cpuTotal) > tolerance) {
                errors.add("Σ byApp.cpuSeconds + unattributedShare.cpuSeconds ≠ process.cpuSecondsTotal");
            }
        }
    }

    private static boolean known(final Set<String> values, final String value) {
        return value != null && values.contains(value);
    }

    private static String text(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isString() ? v.asString() : null;
    }

    private static boolean isNumber(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isNumber() && v.asDouble() >= 0;
    }

    private static boolean isInteger(final JsonNode node, final String field) {
        final JsonNode v = node.get(field);
        return v != null && v.isIntegralNumber() && v.asLong() >= 0;
    }

    private static void optionalInteger(final JsonNode node, final String field, final String prefix, final List<String> errors) {
        final JsonNode v = node.get(field);
        if (v != null && !v.isNull() && !isInteger(node, field)) {
            errors.add(prefix + field + " : integer >= 0 expected");
        }
    }
}
