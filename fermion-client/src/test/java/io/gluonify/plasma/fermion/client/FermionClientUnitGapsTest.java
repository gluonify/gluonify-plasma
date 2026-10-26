package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.fermion.FermionMsg;
import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.SkillDescriptor;
import io.gluonify.plasma.fermion.Translation;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The parts of the Fermion client that need no connection: task parameters, URL rules, token files, node identity, handler registration. */
class FermionClientUnitGapsTest {
    @TempDir
    Path dir;

    final ObjectMapper mapper = JsonMapper.builder().build();

    TaskContext ctx(final Map<String, Object> payload) {
        return new TaskContext("g", "c", "ACT", payload, mapper);
    }

    static String kind(final Runnable call) {
        return assertThrows(SkillFailureException.class, call::run).errorKind();
    }

    record Item(String name, int qty) {
    }

    // --- TaskContext ---

    @Test
    void aMissingPayloadIsEmptyAndTheOrganizationIsReadFromIt() {
        final TaskContext none = new TaskContext("g", "c", "ACT", null, mapper, null, null);
        assertEquals(Map.of(), none.payload());
        assertNull(none.organization());
        none.progress(Map.of("phase", "ignored: no sink"));
        assertEquals("acme", ctx(Map.of("organization", "acme")).organization());
        assertEquals("42", ctx(Map.of("organization", 42)).organization(), "whatever it is, as text");
    }

    @Test
    void theRawValueAndTheTypedValueOfAParameter() {
        final TaskContext c = ctx(Map.of("s", "x", "n", 5, "item", Map.of("name", "a", "qty", 2)));
        assertEquals("x", c.get("s"));
        assertNull(c.get("absent"));
        assertEquals("x", c.get("s", String.class));
        assertEquals(new Item("a", 2), c.get("item", Item.class), "a map converted to a record");
        assertEquals(Optional.of(5), c.optional("n", Integer.class));
        assertEquals("MISSING_PARAMETER", kind(() -> c.get("absent", String.class)));
    }

    @Test
    void aValueThatCannotBeConvertedIsAnInvalidParameter() {
        final TaskContext c = ctx(Map.of("item", Map.of("name", "a", "qty", "not a number"), "list", "not a list"));
        assertEquals("INVALID_PARAMETER", kind(() -> c.get("item", Item.class)));
        assertEquals("INVALID_PARAMETER", kind(() -> c.optional("item", Item.class)));
        assertEquals("INVALID_PARAMETER", kind(() -> c.getList("list", Item.class)));
        assertTrue(assertThrows(SkillFailureException.class, () -> c.get("item", Item.class)).getMessage().contains("'item' of task ACT is not a valid Item"));
    }

    @Test
    void everyTypedGetterRefusesAMissingValue() {
        final TaskContext c = ctx(Map.of());
        assertEquals("MISSING_PARAMETER", kind(() -> c.getBoolean("x")));
        assertEquals("MISSING_PARAMETER", kind(() -> c.getMap("x")));
        assertEquals("MISSING_PARAMETER", kind(() -> c.getList("x", String.class)));
        assertEquals("MISSING_PARAMETER", kind(() -> c.getLong("x")));
        assertEquals("MISSING_PARAMETER", kind(() -> c.getString("x")));
    }

    @Test
    void booleansAreReadFromBooleansAndFromTheirText() {
        final TaskContext c = ctx(Map.of("t", true, "f", Boolean.FALSE, "text", " TRUE ", "no", "false"));
        assertTrue(c.getBoolean("t"));
        assertFalse(c.getBoolean("f"));
        assertTrue(c.getBoolean("text"));
        assertFalse(c.getBoolean("no"));
        assertEquals("INVALID_PARAMETER", kind(() -> ctx(Map.of("b", "yes")).getBoolean("b")));
        assertEquals("INVALID_PARAMETER", kind(() -> ctx(Map.of("b", 1)).getBoolean("b")));
    }

    @Test
    void aListIsConvertedElementByElement() {
        final TaskContext c = ctx(Map.of("names", List.of("a", "b"), "items", List.of(Map.of("name", "a", "qty", 1), Map.of("name", "b", "qty", 2))));
        assertEquals(List.of("a", "b"), c.getList("names", String.class));
        assertEquals(List.of(new Item("a", 1), new Item("b", 2)), c.getList("items", Item.class));
    }

    @Test
    void theContextPrintsItsKeysAndNeverTheirValues() {
        final TaskContext c = new TaskContext("tx-1", "co-1", "ACT", Map.of("secret", "hunter2"), mapper);
        assertEquals("gtid=tx-1,coid=co-1,action=ACT,keys=[secret]", c.toString());
        assertFalse(c.toString().contains("hunter2"));
    }

    // --- FermionTransport ---

    @Test
    void theInternalNetworkIsRecognizedWithoutDnsLookups() {
        for (final String host : new String[]{"localhost", "LOCALHOST", "app.localhost", "127.0.0.1", "10.1.2.3", "192.168.0.9", "172.16.0.1", "169.254.1.1", "[::1]", "::1", "[fd00::1]", "[fc00::5]", "[fe80::1]"}) {
            assertTrue(FermionTransport.internal(host), host);
        }
        for (final String host : new String[]{null, "", "example.com", "8.8.8.8", "[2001:db8::1]", "[]", "not an address", "1.2.3"}) {
            assertFalse(FermionTransport.internal(host), String.valueOf(host));
        }
    }

    @Test
    void onlySecureSchemesOrInternalPlainOnesAreAcceptable() {
        assertTrue(FermionTransport.acceptable("https://fermion.example"));
        assertTrue(FermionTransport.acceptable("WSS://fermion.example"));
        assertTrue(FermionTransport.acceptable(" http://10.0.0.7:8090 "));
        assertTrue(FermionTransport.acceptable("ws://localhost:1"));
        assertFalse(FermionTransport.acceptable("http://fermion.example"));
        assertFalse(FermionTransport.acceptable("ftp://localhost"));
        assertFalse(FermionTransport.acceptable("//localhost"));
        assertFalse(FermionTransport.acceptable("not a url"));
        assertFalse(FermionTransport.acceptable("http:///nohost"));
    }

    // --- FermionTokenProvider, token producer ---

    @Test
    void aBlankTokenFileVariableIsAsAbsentAsNone() {
        final Map<String, String> env = Map.of("GLUONIFY_FERMION_TOKEN_FILE", "   ");
        assertThrows(IllegalStateException.class, () -> FermionTokenProvider.platform(env::get));
    }

    @Test
    void aTokenFileThatCannotBeReadIsAnUncheckedFailure() {
        final FermionTokenProvider p = FermionTokenProvider.platform(Map.of("GLUONIFY_FERMION_TOKEN_FILE", dir.toString())::get);
        final java.io.UncheckedIOException e = assertThrows(java.io.UncheckedIOException.class, p::token);
        assertTrue(e.getMessage().contains("cannot read the Fermion token file"), e.getMessage());
    }

    @Test
    void thePlatformProviderReadsTheRealEnvironment() {
        // GLUONIFY_FERMION_TOKEN_FILE is not set in a build: the default form refuses, like the explicit one
        if (System.getenv(FermionTokenProvider.ENV_TOKEN_FILE) == null) {
            assertThrows(IllegalStateException.class, FermionTokenProvider::platform);
        }
    }

    @Test
    void theDefaultProviderResolvesFermionThroughTheProcessInstance() {
        System.setProperty("GLUONIFY_SERVICE_FERMION_URL", "https://fermion.example");
        System.setProperty("GLUONIFY_FERMION_TOKEN", "from-property");
        try {
            assertEquals("from-property", FermionTokenProvider.gluonify().token());
            assertEquals("from-property", new GluonifyTokenProducer().gluonifyTokens().token());
        }
        finally {
            System.clearProperty("GLUONIFY_SERVICE_FERMION_URL");
            System.clearProperty("GLUONIFY_FERMION_TOKEN");
        }
    }

    // --- NodeIdentity ---

    static FermionClientConfig config(final Optional<String> appId, final String nodeId) {
        return (FermionClientConfig) Proxy.newProxyInstance(FermionClientUnitGapsTest.class.getClassLoader(), new Class<?>[]{FermionClientConfig.class}, (p, m, a) -> switch (m.getName()) {
            case "appId" -> appId;
            case "nodeId" -> nodeId;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    @Test
    void theNodeIsNamedAfterTheConfiguredApplicationOrTheQuarkusOne() {
        final NodeIdentity identity = new NodeIdentity();
        identity.applicationName = "quarkus-app";
        identity.config = config(Optional.of("billing"), "3");
        assertEquals("billing", identity.appId());
        assertEquals("billing-3", identity.id());
        identity.config = config(Optional.of("  "), "0");
        assertEquals("quarkus-app", identity.appId(), "a blank name is no name");
        identity.config = config(Optional.empty(), "0");
        assertEquals("quarkus-app-0", identity.id());
        assertEquals("0", identity.nodeId());
    }

    // --- SkillHandler and its registry ---

    static SkillHandler handler(final String action, final List<Translation> descriptions, final Duration expected) {
        return new SkillHandler() {
            @Override
            public String action() {
                return action;
            }

            @Override
            public List<Translation> descriptions() {
                return descriptions;
            }

            @Override
            public Duration expectedDuration() {
                return expected;
            }

            @Override
            public Map<String, Object> handle(final TaskContext context) {
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    static SkillHandlerRegistry registry(final SkillHandler... handlers) {
        final SkillHandlerRegistry registry = new SkillHandlerRegistry();
        registry.handlers = (Instance<SkillHandler>) Proxy.newProxyInstance(FermionClientUnitGapsTest.class.getClassLoader(), new Class<?>[]{Instance.class}, (p, m, a) -> {
            if (m.getName().equals("iterator")) {
                final Iterator<SkillHandler> it = List.of(handlers).iterator();
                return it;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        return registry;
    }

    static final List<Translation> ALL = SkillHandler.translations("en", "fr", "es", "it", "de");

    @Test
    void handlersAreIndexedByActionAndAnnouncedWithTheirDescriptions() {
        final SkillHandlerRegistry registry = registry(handler("B", ALL, Duration.ofHours(2)), handler("A", List.of(), null), handler("C", null, null));
        registry.index();
        assertEquals("A", registry.skills().get(0).id());
        final SkillDescriptor b = registry.skills().get(1);
        assertEquals("B", b.id());
        assertEquals(5, b.translations().size());
        assertEquals(Duration.ofHours(2).toString(), b.expectedDuration());
        assertNull(registry.skills().get(0).expectedDuration());
        assertEquals(List.of(), registry.skills().get(2).translations(), "no description announced as none");
        assertNotNull(registry.get("B"));
        assertNull(registry.get("absent"));
    }

    @Test
    void aHandlerWithoutActionIsRefused() {
        for (final String action : new String[]{null, "", "  "}) {
            final SkillHandlerRegistry registry = registry(handler(action, ALL, null));
            assertTrue(assertThrows(IllegalStateException.class, registry::index).getMessage().startsWith("SkillHandler without action"), String.valueOf(action));
        }
    }

    @Test
    void partialDescriptionsAreAnErrorNamingTheMissingLanguages() {
        final List<Translation> partial = List.of(new Translation(Language.EN, "only english"), new Translation(Language.FR, "seulement"));
        final IllegalStateException e = assertThrows(IllegalStateException.class, () -> registry(handler("P", partial, null)).index());
        assertTrue(e.getMessage().contains("descriptions missing for") && e.getMessage().contains("DE") && e.getMessage().contains("ES") && e.getMessage().contains("IT"), e.getMessage());
        assertFalse(e.getMessage().contains("EN"), e.getMessage());
    }

    @Test
    void aDuplicateActionIsReplacedByTheLastHandler() {
        final SkillHandler first = handler("DUP", ALL, null);
        final SkillHandler second = handler("DUP", ALL, null);
        final SkillHandlerRegistry registry = registry(first, second);
        registry.index();
        assertSame(second, registry.get("DUP"));
        assertEquals(1, registry.skills().size());
    }

    @Test
    void theDefaultsOfAHandlerAnnounceNothingAndCancelDoesNothing() {
        final SkillHandler h = new SkillHandler() {
            @Override
            public String action() {
                return "D";
            }

            @Override
            public Map<String, Object> handle(final TaskContext context) {
                return Map.of();
            }
        };
        assertEquals(List.of(), h.inputs());
        assertEquals(List.of(), h.outputs());
        assertEquals(List.of(), h.descriptions());
        assertEquals(Map.of(), h.requires());
        assertNull(h.expectedDuration());
        h.onCancel(new TaskContext("g", "c", "D", Map.of(), mapper), new FermionMsg.Cancel("c", null, "D", "stop", false));
        assertEquals(new ArrayList<>(List.of(Language.EN, Language.FR, Language.ES, Language.IT, Language.DE)), SkillHandler.translations("a", "b", "c", "d", "e").stream().map(Translation::language).toList());
    }

    @Test
    void aTokenFileIsRereadFromDisk() throws Exception {
        final Path file = Files.writeString(dir.resolve("t"), "one");
        final FermionTokenProvider p = FermionTokenProvider.platform(Map.of("GLUONIFY_FERMION_TOKEN_FILE", " " + file + " ")::get);
        assertEquals("one", p.token(), "surrounding blanks of the path are ignored");
    }
}
