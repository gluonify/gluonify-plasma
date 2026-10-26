package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.analysis.AnalysisReport;
import io.gluonify.plasma.analysis.Finding;
import io.gluonify.plasma.analysis.FindingKind;
import io.gluonify.plasma.analysis.Location;
import io.gluonify.plasma.analysis.Severity;
import io.gluonify.plasma.fermion.NodeCapacity;
import io.gluonify.plasma.release.TaskCatalogue;
import io.gluonify.plasma.store.StoreCapability;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The application specification and the smaller domain rules: every refusal of a declaration, window, task, capability and finding. */
class DomainRulesGapsTest {

    /** Builds an {@code AppSpec} from a valid minimal base and the given overrides, whatever the number of its components. */
    static AppSpec spec(final Map<String, Object> overrides) {
        final RecordComponent[] components = AppSpec.class.getRecordComponents();
        final Object[] values = new Object[components.length];
        final Class<?>[] types = new Class<?>[components.length];
        final Map<String, Object> base = new HashMap<>(Map.of("name", "shop", "image", "registry/shop:1.0"));
        base.putAll(overrides);
        for (int i = 0; i < components.length; i++) {
            types[i] = components[i].getType();
            values[i] = base.get(components[i].getName());
        }
        try {
            final Constructor<AppSpec> c = AppSpec.class.getDeclaredConstructor(types);
            return c.newInstance(values);
        }
        catch (final InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException r) {
                throw r;
            }
            throw new IllegalStateException(e);
        }
        catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void refuses(final Map<String, Object> overrides, final String fragment) {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> spec(overrides));
        assertTrue(e.getMessage().contains(fragment), fragment + " expected in " + e.getMessage());
    }

    static Map<String, Object> with(final Object... pairs) {
        final Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], pairs[i + 1]);
        }
        return m;
    }

    // --- application specification ---

    @Test
    void theMinimalSpecificationGetsItsDefaults() {
        final AppSpec s = spec(Map.of());
        assertEquals(1, s.replicas());
        assertEquals(8080, s.port());
        assertEquals(64, s.memoryMb());
        assertEquals(AppSpec.DEFAULT_SCRATCH_MB, s.scratchMb());
        assertFalse(s.nas());
        assertFalse(s.internal());
        assertNull(s.mcp());
        assertFalse(s.sleepEnabled());
        assertFalse(s.mcpEnabled());
        assertNull(s.appNamespace());
        assertNull(s.gluonifyNamespace());
        assertEquals(1, s.requiredX86Level("amd64"));
        assertEquals(Organizations.UNASSIGNED, s.organizationOrUnassigned());
    }

    @Test
    void numbersNamesAndFormatsAreChecked() {
        refuses(with("replicas", 101), "replicas out of range");
        refuses(with("replicas", -1), "replicas out of range");
        refuses(with("port", 0), "invalid port");
        refuses(with("port", 65_536), "invalid port");
        refuses(with("memoryMb", 8), "minimum memory");
        refuses(with("scratchMb", -1), "scratchMb out of range");
        refuses(with("scratchMb", AppSpec.MAX_SCRATCH_MB + 1), "scratchMb out of range");
        refuses(with("name", "api"), "reserved name");
        refuses(with("name", "ns1"), "reserved name");
        refuses(with("name", "Bad Name"), "invalid application name");
        refuses(with("name", null), "invalid application name");
        assertEquals("nsa-long", spec(with("name", "nsa-long")).name(), "only the short ns names are reserved");
        refuses(with("image", null), "image or artifacts required");
        refuses(with("image", " "), "image or artifacts required");
        refuses(with("pin", "bad pin!"), "invalid pin");
        refuses(with("role", "UPPER"), "invalid role");
        refuses(with("pullSecret", "token"), "pullSecret");
        refuses(with("distributedQuotaMb", 0, "distributed", List.of("std")), "distributedQuotaMb");
        refuses(with("topNamespace", "Bad_NS"), "invalid topNamespace");
        refuses(with("uuid", "not-a-uuid"), "invalid uuid");
        refuses(with("provenance", "garbage!"), "provenance: unreadable token");
        refuses(with("artifactLevel", "amd64:v9"), "artifactLevel");
        assertEquals("amd64:v3", spec(with("artifactLevel", " AMD64:V3 ")).artifactLevel());
        assertNull(spec(with("artifactLevel", " ")).artifactLevel());
        assertNull(spec(with("provenance", " ")).provenance());
        assertTrue(spec(with("mcp", true)).mcpEnabled());
        assertNull(spec(with("mcp", false)).mcp());
        assertEquals(3, spec(with("artifactLevel", "amd64:v3")).requiredX86Level("amd64"));
        assertEquals(1, spec(with("artifactLevel", "amd64:v3")).requiredX86Level("arm64"));
        assertEquals("0b6f7c1e-0000-4000-8000-000000000001.app", spec(with("uuid", "0b6f7c1e-0000-4000-8000-000000000001")).appNamespace());
        assertEquals("0b6f7c1e-0000-4000-8000-000000000001.gluonify", spec(with("uuid", "0b6f7c1e-0000-4000-8000-000000000001")).gluonifyNamespace());
    }

    @Test
    void artifactsMustNameKnownArchitecturesAndRealExecutables() {
        refuses(with("image", null, "artifacts", Map.of("sparc", "https://x#sha256=" + "a".repeat(64))), "unknown architecture: sparc");
        refuses(with("image", null, "artifacts", Map.of("amd64", " ")), "invalid executable for amd64");
        refuses(with("image", null, "artifacts", Map.of("amd64", "x".repeat(2049))), "invalid executable for amd64");
        final Map<String, String> nullValue = new HashMap<>();
        nullValue.put("amd64", null);
        refuses(with("image", null, "artifacts", nullValue), "invalid executable for amd64");
        assertEquals(List.of("amd64", "arm64"), List.copyOf(spec(with("image", null, "artifacts", Map.of("arm64", "u2", "amd64", "u1"))).artifacts().keySet()));
    }

    @Test
    void portsEnvironmentSecretsAndServicesAreBounded() {
        refuses(with("ports", List.of(9100, 9101, 9102, 9103, 9104)), "too many additional ports");
        refuses(with("ports", List.of(80)), "invalid additional port");
        refuses(with("ports", List.of(25_000)), "invalid additional port");
        refuses(with("ports", List.of(70_000)), "invalid additional port");
        refuses(with("ports", List.of(9100, 9100)), "duplicate ports");
        refuses(with("ports", List.of(8080)), "duplicate ports");
        assertEquals(List.of(9100, 30_000), spec(with("ports", List.of(9100, 30_000))).ports());

        final Map<String, String> tooMany = new HashMap<>();
        for (int i = 0; i < 65; i++) {
            tooMany.put("VAR_" + i, "v");
        }
        refuses(with("env", tooMany), "too many variables");
        refuses(with("env", Map.of("lower", "v")), "invalid variable name");
        refuses(with("env", Map.of("QUARKUS_HTTP_PORT", "1")), "reserved to the PaaS");
        refuses(with("env", Map.of("GLUONIFY_SERVICE_X_URL", "1")), "reserved to the PaaS");
        refuses(with("env", Map.of("GLUONIFY_GDOWN_BOLT_URL", "1")), "reserved to the PaaS");
        refuses(with("env", Map.of("OK", "x".repeat(4097))), "invalid value for OK");
        final Map<String, String> nullEnv = new HashMap<>();
        nullEnv.put("OK", null);
        refuses(with("env", nullEnv), "invalid value for OK");

        final List<String> manySecrets = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            manySecrets.add("SECRET_" + i);
        }
        refuses(with("generated", manySecrets), "too many generated secrets");
        refuses(with("generated", List.of("lower")), "invalid generated secret");
        refuses(with("generated", List.of("QUARKUS_HTTP_HOST")), "invalid generated secret");
        refuses(with("generated", List.of("SAME"), "env", Map.of("SAME", "v")), "invalid generated secret");

        final List<String> manyUses = new ArrayList<>();
        for (int i = 0; i < 17; i++) {
            manyUses.add("svc" + i);
        }
        refuses(with("uses", manyUses), "too many services used");
        refuses(with("uses", List.of("Bad Service")), "invalid service");
        assertEquals(List.of("gdown", "?crystallon"), spec(with("uses", List.of("gdown", "?crystallon"))).uses());
    }

    @Test
    void distributedFileSystemsLocksAndSleepModeMustBeConsistent() {
        refuses(with("distributed", List.of("nfs")), "distributed: \"std\", \"tmp\" or \"gdown\"");
        refuses(with("distributed", List.of("std", "std")), "distributed: duplicate");
        refuses(with("distributedLocks", true), "distributedLocks: no distributed file system declared");
        refuses(with("distributedQuotaMb", 10), "distributedQuotaMb: no distributed file system declared");
        final SleepSpec sleep = new SleepSpec(true, 30, 600, 5000);
        refuses(with("sleep", sleep, "internal", true), "an internal application has no public route");
        refuses(with("sleep", sleep, "ports", List.of(9100)), "incompatible with additional ports");
        refuses(with("sleep", sleep, "role", "storage"), "incompatible with a node role");
        refuses(with("sleep", sleep, "distributed", List.of("std"), "distributedLocks", true), "incompatible with distributedLocks");
        assertTrue(spec(with("sleep", sleep)).sleepEnabled());
        assertFalse(spec(with("sleep", SleepSpec.off(), "internal", true)).sleepEnabled(), "a disabled sleep mode has no constraint");
        assertNotNull(spec(with("distributed", List.of("std", "tmp", "gdown"), "distributedLocks", true, "distributedQuotaMb", 100)));
    }

    @Test
    void theSleepSpecificationIsBounded() {
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 0, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 86_401, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 60, SleepSpec.MAX_SECONDS + 1, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 60, 60, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 60, 900, 99));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec(true, 60, 900, 60_001));
        assertEquals(960, new SleepSpec(true, 900, null, null).stopAfterSeconds(), "the default stop leaves a minute after the freeze");
        assertEquals(SleepSpec.DEFAULT_STOP_SECONDS, new SleepSpec(true, null, null, null).stopAfterSeconds());
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.Wake(0, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.Wake(101, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(-1, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, 100_001, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, 60_001, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, null, 1_000_001, null, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, null, null, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, null, null, 101, null));
        assertThrows(IllegalArgumentException.class, () -> new SleepSpec.AdditionalWhen(null, null, null, null, null, 86_401));
        assertNotNull(new SleepSpec(true, 60, 900, 5000, new SleepSpec.Wake(2, new SleepSpec.AdditionalWhen(0, 1, 500, 0, 1, 10))));
    }

    // --- Python, gdown, packages ---

    @Test
    void gdownUsageIsMandatoryOrOptional() {
        assertTrue(GdownBolt.usesGdown(List.of("gdown")));
        assertTrue(GdownBolt.usesGdown(List.of("crystallon", "?gdown")));
        assertFalse(GdownBolt.usesGdown(List.of("crystallon")));
        assertFalse(GdownBolt.usesGdown(List.of()));
        assertFalse(GdownBolt.usesGdown(null));
    }

    @Test
    void requiredPackagesAreCheckedSortedAndBounded() {
        assertTrue(Requires.NONE.isEmpty());
        assertTrue(new Requires(null).isEmpty());
        final Requires r = new Requires(Map.of("debian13", List.of("zlib1g", "curl", "curl"), "ubuntu24.04", List.of(), "alpine", java.util.Arrays.asList((String) null).subList(0, 0)));
        assertEquals(List.of("curl", "zlib1g"), r.forDistro("debian13"));
        assertEquals(List.of(), r.forDistro("ubuntu24.04"), "a distribution without packages is dropped");
        assertEquals(List.of(), r.forDistro("unknown"));
        assertFalse(r.isEmpty());
        assertThrows(IllegalArgumentException.class, () -> new Requires(Map.of("Debian", List.of("curl"))));
        assertThrows(IllegalArgumentException.class, () -> new Requires(Map.of("debian13", List.of("-rf"))));
        assertThrows(IllegalArgumentException.class, () -> new Requires(Map.of("debian13", java.util.Arrays.asList("curl", null))));
        final List<String> many = new ArrayList<>();
        for (int i = 0; i <= Requires.MAX_PACKAGES; i++) {
            many.add("package" + i);
        }
        assertThrows(IllegalArgumentException.class, () -> new Requires(Map.of("debian13", many)));
        final Map<String, List<String>> nullDistro = new HashMap<>();
        nullDistro.put(null, List.of("curl"));
        assertThrows(IllegalArgumentException.class, () -> new Requires(nullDistro));
        assertEquals("debian13", Requires.distroId("Debian", "13"));
        assertEquals("ubuntu24.04", Requires.distroId("ubuntu", "24.04"));
        assertEquals("", Requires.distroId(null, null));
        assertEquals("rhel", Requires.distroId("rhel!", null));
    }

    // --- store capability ---

    @Test
    void aStoreCapabilityIsSignedVerifiedAndExpires() {
        final StoreCapability.Cap cap = new StoreCapability.Cap("put", "ab".repeat(32), 1000, 2000, "ns");
        final String token = StoreCapability.sign("secret", cap);
        assertEquals(cap, StoreCapability.verify("secret", token, 1999));
        assertNull(StoreCapability.verify("secret", token, 2000), "expired at the expiry instant");
        assertNull(StoreCapability.verify("other-secret", token, 1000));
        assertNull(StoreCapability.verify("secret", null, 1000));
        assertNull(StoreCapability.verify("secret", "nonsense", 1000));
        assertNull(StoreCapability.verify("secret", "cap.onlyonepart", 1000));
        assertNull(StoreCapability.verify("secret", "cap.a.b.c", 1000));
        assertNull(StoreCapability.verify("secret", "cap.!!!.!!!", 1000));
        assertEquals("get", StoreCapability.verify("secret", StoreCapability.sign("secret", new StoreCapability.Cap("get", "x", 0, 3000, "ns")), 1).op());
        assertNull(StoreCapability.verify("secret", StoreCapability.sign("secret", new StoreCapability.Cap("delete", "x", 0, 3000, "ns")), 1), "only put and get exist");
        assertNull(StoreCapability.verify("secret", StoreCapability.sign("secret", new StoreCapability.Cap("put", "x|y", 0, 3000, "ns")), 1), "a separator inside a field breaks the format");
        final String notNumbers = "cap." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("put|x|many|soon|ns".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertNull(StoreCapability.verify("secret", notNumbers + ".AAAA", 1));
    }

    // --- tasks ---

    @Test
    void aTaskIsValidatedForItsModeAndItsParameters() {
        assertEquals(Map.of(), TaskCatalogue.validate("report-status", null, TaskCatalogue.AGENT));
        assertEquals(Map.of("version", "1.2.3"), TaskCatalogue.validate("install-wireguard", Map.of("version", "1.2.3"), TaskCatalogue.BOOTSTRAP));
        for (final String[] bad : new String[][]{{null, "agent"}, {"no-such-task", "agent"}, {"install-strange", "agent"}, {"update", "bootstrap"}}) {
            assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate(bad[0], Map.of(), bad[1]), String.join("/", String.valueOf(bad[0]), bad[1]));
        }
        assertTrue(assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", Map.of(), "bootstrap")).getMessage().contains("missing mandatory parameter: version"));
        assertTrue(assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", Map.of("version", "latest"), "bootstrap")).getMessage().contains("invalid value"));
        assertTrue(assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", Map.of("version", "1.2.3", "extra", "x"), "bootstrap")).getMessage().contains("unknown parameter"));
        final Map<String, String> nullValue = new HashMap<>();
        nullValue.put("version", null);
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", nullValue, "bootstrap"));
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", Map.of("version", "1.2.3-" + "x".repeat(600)), "bootstrap"));
        final Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i <= TaskCatalogue.MAX_PARAMS; i++) {
            many.put("p" + i, "v");
        }
        assertTrue(assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-wireguard", many, "bootstrap")).getMessage().contains("too many parameters"));
    }

    @Test
    void secretParametersAreMaskedAndTheTotalSizeIsBounded() {
        final Map<String, String> higgs = new LinkedHashMap<>(Map.of("version", "1.2.3", "zone", "gluonify.lan", "publicIp", "10.0.0.1", "httpUrl", "https://gluonify.lan",
                "nodeId", "n1", "advertise", "gluonify.lan", "seed", "seed.lan:9000", "seedToken", "secret-token-value"));
        assertEquals(higgs, TaskCatalogue.validate("install-higgs", higgs, "bootstrap"));
        final Map<String, String> masked = TaskCatalogue.masked("install-higgs", higgs);
        assertEquals("***", masked.get("seedToken"));
        assertEquals("1.2.3", masked.get("version"));
        assertEquals(Map.of(), TaskCatalogue.masked("install-higgs", null));
        assertEquals(Map.of("anything", "kept"), TaskCatalogue.masked("no-such-type", Map.of("anything", "kept")));
        assertEquals(Map.of("unknown", "kept"), TaskCatalogue.masked("report-status", Map.of("unknown", "kept")));
        // 5 parameters of 512 characters exceed the total of 2048 (each value is within its own limit)
        final Map<String, String> big = new LinkedHashMap<>(higgs);
        big.put("seedToken", "t".repeat(256));
        big.put("acmeEmail", "a".repeat(64) + "@" + "b".repeat(200));
        big.put("httpUrl", "https://" + "h".repeat(250) + ".example");
        big.put("zone", "z".repeat(250));
        big.put("publicIp", "1".repeat(45));
        big.put("advertise", "a".repeat(250));
        big.put("seed", "s".repeat(250) + ":9000");
        big.put("nodeId", "n".repeat(40));
        assertThrows(TaskCatalogue.Invalid.class, () -> TaskCatalogue.validate("install-higgs", big, "bootstrap"));
    }

    // --- capacity windows ---

    @Test
    void capacityWindowsAreParsedCheckedAndEvaluatedInTheirZone() {
        assertTrue(NodeCapacity.of(Map.of(), List.of(), null).isEmpty());
        assertFalse(NodeCapacity.of(Map.of("heavy", 1), List.of(), null).isEmpty());
        assertFalse(NodeCapacity.of(Map.of(), List.of("23:30-07:00"), null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.of(Map.of("heavy", -1), List.of(), null));
        final Map<String, Integer> nullSlot = new HashMap<>();
        nullSlot.put("heavy", null);
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.of(nullSlot, List.of(), null));
        final NodeCapacity.Window night = NodeCapacity.Window.parse("23:30-07:00", null);
        assertEquals(NodeCapacity.Window.DEFAULT_ZONE, night.zone());
        assertEquals("23:30-07:00", night.toString());
        assertTrue(night.contains(Instant.parse("2026-10-26T22:45:00Z")), "23:45 in Paris");
        assertTrue(night.contains(Instant.parse("2026-10-27T03:00:00Z")), "04:00 in Paris, after midnight");
        assertFalse(night.contains(Instant.parse("2026-10-27T10:00:00Z")));
        final NodeCapacity.Window saturday = NodeCapacity.Window.parse("sat 00:00-00:00", "UTC");
        assertEquals("SAT 00:00-00:00", saturday.toString());
        assertTrue(saturday.contains(Instant.parse("2026-10-31T12:00:00Z")), "a Saturday: the whole day");
        assertFalse(saturday.contains(Instant.parse("2026-10-30T12:00:00Z")));
        assertFalse(saturday.contains(Instant.parse("2026-11-01T02:00:00Z")), "from == to closes the day it starts, up to midnight: not the Sunday after");
        final NodeCapacity.Window day = NodeCapacity.Window.parse("MON,TUE 09:00-17:00", "Europe/Paris");
        assertTrue(day.contains(Instant.parse("2026-10-26T10:00:00Z")));
        assertFalse(day.contains(Instant.parse("2026-10-28T10:00:00Z")), "Wednesday");
        assertFalse(day.contains(Instant.parse("2026-10-26T16:30:00Z")), "17:30 in Paris is after the end");
        assertTrue(NodeCapacity.of(Map.of(), List.of("09:00-17:00"), "UTC").closedAt(Instant.parse("2026-10-26T10:00:00Z")));
        assertFalse(NodeCapacity.of(Map.of(), List.of("09:00-17:00"), "UTC").closedAt(Instant.parse("2026-10-26T18:00:00Z")));
        for (final String bad : new String[]{"nonsense", "25:00-26:00", "09:00-", "XXX 09:00-17:00", "MO 09:00-17:00"}) {
            assertThrows(IllegalArgumentException.class, () -> NodeCapacity.Window.parse(bad, "UTC"), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.Window.parse(null, "UTC"));
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.Window.parse("09:00-17:00", "Mars/Olympus"));
        assertThrows(IllegalArgumentException.class, () -> new NodeCapacity.Window(null, "17:00", null, null));
        assertEquals(NodeCapacity.Window.DEFAULT_ZONE, new NodeCapacity.Window("09:00", "17:00", null, " ").zone());
        assertTrue(new NodeCapacity(null, null).isEmpty());
    }

    // --- analysis findings ---

    @Test
    void aFindingIsCheckedAndItsDefaultsAreFilled() {
        final Finding f = new Finding(null, "id", FindingKind.NATIVE, "N-ELF", Severity.MAJOR, null, null, null, null, null);
        assertEquals(Finding.CURRENT_VERSION, f.v());
        assertFalse(f.blocking());
        assertEquals(Map.of(), f.args());
        assertEquals(io.gluonify.plasma.analysis.FindingStatus.OPEN, f.status());
        assertEquals(Finding.CURRENT_VERSION, new Finding(0, "id", FindingKind.NATIVE, "N-ELF", Severity.MAJOR, null, null, null, null, null).v());
        assertNotNull(new Finding(null, "id", FindingKind.SONAR, "gluonify:GN04", Severity.MINOR, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Finding(null, "id", FindingKind.NATIVE, null, Severity.MAJOR, null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Finding(null, "id", FindingKind.NATIVE, "lower-case", Severity.MAJOR, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new Finding(null, "id", null, "N-ELF", Severity.MAJOR, null, null, null, null, null));
        assertThrows(NullPointerException.class, () -> new Finding(null, "id", FindingKind.NATIVE, "N-ELF", null, null, null, null, null, null));
        final Map<String, String> many = new LinkedHashMap<>();
        for (int i = 0; i <= Finding.MAX_ARGS; i++) {
            many.put("arg" + i, "v");
        }
        assertThrows(IllegalArgumentException.class, () -> new Finding(null, "id", FindingKind.NATIVE, "N-ELF", Severity.MAJOR, null, null, many, null, null));
        assertThrows(IllegalArgumentException.class, () -> new Finding(null, "id", FindingKind.NATIVE, "N-ELF", Severity.MAJOR, null, null, Map.of("1bad", "v"), null, null));
        final Finding withArgs = new Finding(null, "id", FindingKind.NATIVE, "N-ELF", Severity.MAJOR, null, null, Map.of("type", "x".repeat(2000)), null, null);
        assertEquals(Finding.MAX_ARG_LENGTH, withArgs.args().get("type").length());
        assertEquals("", Finding.arg(null));
        assertEquals("7", Finding.arg(7));
        assertEquals("ref-1", f.withRef("ref-1").ref());
        assertEquals(io.gluonify.plasma.analysis.FindingStatus.FIXED, f.withStatus(io.gluonify.plasma.analysis.FindingStatus.FIXED).status());
    }

    @Test
    void identifiersAreStableAndLocationsRelative() {
        final String id = Finding.idOf(FindingKind.NATIVE, "build", "N-ELF", Location.of("src/Main.java"));
        assertEquals(64, id.length());
        assertEquals(id, Finding.idOf(FindingKind.NATIVE, "build", "N-ELF", new Location("src/Main.java", null)));
        assertNotEquals(id, Finding.idOf(FindingKind.NATIVE, "build", "N-ELF", new Location("src/Main.java", 3)));
        assertEquals(Finding.idOf(FindingKind.NATIVE, null, "N-ELF", null), Finding.idOf(FindingKind.NATIVE, "", "N-ELF", null));
        assertEquals("src/Main.java:3", new Location("src/Main.java", 3).key());
        for (final String bad : new String[]{null, " ", "/abs", "a\\b", "C:/x", "a/../b"}) {
            assertThrows(IllegalArgumentException.class, () -> Location.of(bad), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new Location("a", 0));
    }

    @Test
    void aReportSortsItsFindingsAndKeepsItsSummaryCountRight() {
        final Finding minor = new Finding(null, "b", FindingKind.SONAR, "GN01", Severity.MINOR, false, null, null, null, null);
        final Finding blocker = new Finding(null, "a", FindingKind.NATIVE, "N-ELF", Severity.BLOCKER, true, null, null, null, null);
        final Finding majorBlocking = new Finding(null, "c", FindingKind.DEPS, "CVE-1", Severity.MAJOR, true, null, null, null, null);
        final Finding majorOther = new Finding(null, "d", FindingKind.DEPS, "CVE-2", Severity.MAJOR, false, null, null, null, null);
        final Finding noId = new Finding(null, null, FindingKind.DEPS, "CVE-2", Severity.MAJOR, false, null, null, null, null);
        final AnalysisReport r = new AnalysisReport(null, "build-1", null, List.of(minor, majorOther, noId, blocker, majorBlocking));
        assertEquals(List.of("a", "c", "d", "b").size() + 1, r.findings().size());
        assertEquals(Severity.BLOCKER, r.findings().get(0).severity());
        assertTrue(r.findings().get(1).blocking());
        assertNull(r.findings().get(2).id(), "no identifier sorts first among equals");
        assertTrue(r.blocked());
        assertFalse(new AnalysisReport(1, null, null, List.of(minor)).blocked());
        assertFalse(new AnalysisReport(1, null, null, List.of(blocker.withStatus(io.gluonify.plasma.analysis.FindingStatus.ACCEPTED))).blocked(), "an accepted finding does not block");
        assertEquals(List.of(), new AnalysisReport(null, null, null, null).findings());
        assertTrue(Severity.CRITICAL.atLeast(Severity.MAJOR));
        assertFalse(Severity.INFO.atLeast(Severity.MINOR));
        assertEquals(Severity.MAJOR, Severity.parse(" Major "));
        assertThrows(IllegalArgumentException.class, () -> Severity.parse(null));
        assertThrows(IllegalArgumentException.class, () -> Severity.parse("catastrophic"));
    }
}
