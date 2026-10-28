package io.gluonify.plasma.usage;

import io.gluonify.plasma.analysis.AnalysisUnits;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Closed catalog of usage units ({@code metering.md} § 3.1 and {@code analysis.md} § 10), shipped by the SDK so that Higgs validates price grids without
 * calling the calorimeter ({@code contracts.md} § 2.4). A name outside this catalog can be neither recorded nor priced.
 */
public final class UsageCatalog {
    private static final String SERVICE_GDOWN = "gdown";
    private UsageCatalog() {}

    private static final String KIND = "kind";
    private static final List<String> VFS_KINDS = List.of("std", "tmp", SERVICE_GDOWN);
    private static final String MODEL = "model";
    private static final String ENGINE = "engine";
    private static final String AGENT = "agent";
    private static final String TICKET = "ticket";

    /** Models of gluonify-superposition's engines (dimension {@code model} of its units). */
    private static final List<String> SUPERPOSITION_MODELS = List.of("qwen3-coder", "haiku", "sonnet", "opus", "fable");

    /** Engines of gluonify-superposition (dimension {@code engine} of its units). */
    private static final List<String> SUPERPOSITION_ENGINES = List.of("edge", "claude");

    private static final Map<String, UsageUnit> UNITS = new LinkedHashMap<>();

    private static void unit(final String name, final Quantity q, final boolean billable, final boolean internal, final Map<String, List<String>> dims) {
        UNITS.put(name, new UsageUnit(name, name.substring(0, name.indexOf('.')), q, billable, internal, dims));
    }

    private static void unit(final String name, final Quantity q, final Map<String, List<String>> dims, final Set<String> free, final Set<String> highCardinality) {
        UNITS.put(name, new UsageUnit(name, name.substring(0, name.indexOf('.')), q, false, false, dims, free, highCardinality));
    }

    private static void unit(final String name, final Quantity q) { unit(name, q, true, false, Map.of()); }

    private static void unit(final String name, final Quantity q, final String dim, final List<String> values) { unit(name, q, true, false, Map.of(dim, values)); }

    static {
        unit("strange.instance.memory_reserved", Quantity.MB_SECONDS, "state", List.of("active", "frozen"));
        unit("strange.instance.cpu", Quantity.SECONDS);
        unit("strange.instance.memory_used", Quantity.MB_SECONDS, false, false, Map.of());
        unit("strange.instance.net_egress", Quantity.BYTES);
        unit("strange.instance.net_ingress", Quantity.BYTES);
        unit("strange.instance.disk_state", Quantity.MB_SECONDS);
        unit("strange.vfs.bytes_written", Quantity.BYTES, KIND, VFS_KINDS);
        unit("strange.vfs.bytes_read", Quantity.BYTES, KIND, VFS_KINDS);
        unit("strange.vfs.ops", Quantity.COUNT, KIND, VFS_KINDS);
        unit("higgs.ingress.requests", Quantity.COUNT, "class", List.of("2xx", "3xx", "4xx", "5xx"));
        unit("higgs.ingress.bytes_in", Quantity.BYTES);
        unit("higgs.ingress.bytes_out", Quantity.BYTES);
        unit("higgs.ingress.ws_seconds", Quantity.OBJECT_SECONDS);
        unit("higgs.ingress.wakes", Quantity.COUNT);
        unit("higgs.domain.count", Quantity.OBJECT_SECONDS);
        unit("higgs.acme.certificates", Quantity.COUNT);
        unit("higgs.storage.blob_bytes", Quantity.GB_HOURS);
        unit("higgs.locks.ops", Quantity.COUNT);
        unit("rup.vfs.stored_bytes", Quantity.GB_HOURS, KIND, VFS_KINDS);
        unit("gdown.db.queries", Quantity.COUNT, KIND, List.of("read", "write"));
        unit("gdown.db.query_seconds", Quantity.SECONDS);
        unit("gdown.db.rows", Quantity.COUNT);
        unit("gdown.db.stored_bytes", Quantity.GB_HOURS);
        unit("gdown.db.memory_reserved", Quantity.MB_SECONDS, false, false, Map.of());
        unit("charm.tokens.issued", Quantity.COUNT, KIND, List.of("service", "node", "user"));
        unit("charm.logins", Quantity.COUNT);
        unit("top.env.reads", Quantity.COUNT);
        unit("top.entries", Quantity.OBJECT_SECONDS);
        unit("crystallon.render.documents", Quantity.COUNT);
        unit("crystallon.render.pages", Quantity.COUNT);
        unit("crystallon.convert.files", Quantity.COUNT, "op", List.of("image", "pdfa", "merge"));
        unit("crystallon.convert.bytes_in", Quantity.BYTES, "op", List.of("image", "pdfa", "merge"));
        unit("crystallon.store.bytes", Quantity.GB_HOURS);
        unit("interfero.messages", Quantity.COUNT, "channel", List.of("email", "teams", "postal"));
        unit("interfero.postal.pages", Quantity.COUNT);
        unit("fermion.executions", Quantity.COUNT);
        unit("fermion.steps", Quantity.COUNT);
        unit("fermion.decisions", Quantity.COUNT);
        unit("photon.hooks.received", Quantity.COUNT);
        unit("photon.hooks.delivered", Quantity.COUNT);
        unit("photon.hooks.attempts", Quantity.COUNT);
        unit("photon.hooks.bytes", Quantity.BYTES);
        unit("photon.hooks.stored_bytes", Quantity.GB_HOURS);
        unit("field.site.requests", Quantity.COUNT);
        unit("field.site.bytes_out", Quantity.BYTES);
        unit("field.site.stored_bytes", Quantity.GB_HOURS);
        unit("calorimeter.logs.stored_bytes", Quantity.GB_HOURS);
        unit("calorimeter.metrics.samples", Quantity.COUNT);
        unit("bup.build.seconds", Quantity.SECONDS, true, false, Map.of("arch", List.of("amd64", "arm64"), "mode", List.of("native", "jvm")));
        unit("bup.builds", Quantity.COUNT, "result", List.of("success", "failure"));
        unit("gauge.mcp.calls", Quantity.COUNT, "result", List.of("ok", "proposed"));
        // gluonify-superposition (architecture § 11): indicative, never a billing basis; cost in USD (decimal), as reported by the engine
        // free: the agent id and the tracker key of the ticket (unbounded: highCardinality, rolled up after usage.high-cardinality-retention)
        unit("superposition.tokens", Quantity.COUNT, Map.of(MODEL, SUPERPOSITION_MODELS, KIND, List.of("input", "output", "cache_read", "cache_write"), ENGINE, SUPERPOSITION_ENGINES),
                Set.of(AGENT, TICKET), Set.of(TICKET));
        unit("superposition.runs", Quantity.COUNT, Map.of("level", List.of("L0", "L1", "L2", "L3"), ENGINE, SUPERPOSITION_ENGINES, MODEL, SUPERPOSITION_MODELS,
                "outcome", List.of("success", "failure", "interrupted", "timeout")), Set.of(AGENT, TICKET), Set.of(TICKET));
        unit("superposition.cost.usd", Quantity.COUNT, Map.of(MODEL, SUPERPOSITION_MODELS, ENGINE, SUPERPOSITION_ENGINES), Set.of(AGENT, TICKET), Set.of(TICKET));
        // analyses (analysis.md § 10)
        unit(AnalysisUnits.BUP_ANALYSIS_RUNS, Quantity.COUNT, AnalysisUnits.DIM_KIND, AnalysisUnits.ANALYSIS_KINDS);
        unit(AnalysisUnits.BUP_ANALYSIS_SECONDS, Quantity.SECONDS);
        unit(AnalysisUnits.BUP_APITESTS_RUNS, Quantity.COUNT);
        unit(AnalysisUnits.BUP_APITESTS_REQUESTS, Quantity.COUNT);
        unit(AnalysisUnits.BUP_JOBS_SECONDS, Quantity.SECONDS, true, true, Map.of(AnalysisUnits.DIM_KIND, AnalysisUnits.JOB_KINDS));
        unit(AnalysisUnits.GEIGER_CHECKS_RUNS, Quantity.COUNT, AnalysisUnits.DIM_KIND, AnalysisUnits.CHECK_KINDS);
        unit(AnalysisUnits.GEIGER_SEO_RUNS, Quantity.COUNT);
        unit(AnalysisUnits.GEIGER_SEO_PAGES, Quantity.COUNT);
        unit(AnalysisUnits.GEIGER_ZAP_RUNS, Quantity.COUNT, AnalysisUnits.DIM_MODE, AnalysisUnits.ZAP_MODES);
        unit(AnalysisUnits.GEIGER_ZAP_REQUESTS, Quantity.COUNT);
    }

    private static final Map<String, UsageUnit> VIEW = Collections.unmodifiableMap(UNITS);

    /** The unit with this name, or null if not in the catalog. */
    public static UsageUnit get(final String name) { return name == null ? null : VIEW.get(name); }

    /** All the units, in catalog order. */
    public static Collection<UsageUnit> all() { return VIEW.values(); }

    /** The names, in catalog order. */
    public static List<String> names() { return List.copyOf(VIEW.keySet()); }
}
