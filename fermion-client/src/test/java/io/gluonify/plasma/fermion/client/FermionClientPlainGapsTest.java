package io.gluonify.plasma.fermion.client;

import static io.gluonify.plasma.fermion.client.PlainFermionFixtures.config;
import static io.gluonify.plasma.fermion.client.PlainFermionFixtures.instance;
import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.fermion.FermionMsg;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The Fermion client without a server: which URL it settles on, how it connects, queues, replays and reconnects, with every failure the network can bring. */
class FermionClientPlainGapsTest {
    static final String PROBE_URL = "GLUONIFY_SERVICE_FERMION_URL";

    final ObjectMapper mapper = JsonMapper.builder().build();
    final PlainFermionFixtures.Link first = new PlainFermionFixtures.Link("c-1");
    final PlainFermionFixtures.Link[] next = {first};
    final List<String> connectorCalls = new CopyOnWriteArrayList<>();
    volatile String token = "tok";

    @AfterEach
    void clean() {
        System.clearProperty(PROBE_URL);
    }

    FermionClient client(final Map<String, Object> overrides) {
        final FermionClient c = new FermionClient();
        c.config = config(overrides);
        c.identity = new NodeIdentity();
        c.identity.config = c.config;
        c.identity.applicationName = "orders";
        final SkillHandlerRegistry registry = new SkillHandlerRegistry();
        registry.handlers = instance(List.of());
        registry.index();
        c.registry = registry;
        c.usages = instance(List.of());
        c.tokens = instance(List.<FermionTokenProvider>of(() -> token));
        c.connectorFactory = PlainFermionFixtures.connectors(next, connectorCalls);
        c.mapper = mapper;
        return c;
    }

    FermionClient active() {
        return client(Map.of("url", Optional.of("ws://localhost:1")));
    }

    // --- the URL ---

    @Test
    void theConfiguredUrlIsStrippedAndMakesTheClientActive() {
        final FermionClient c = client(Map.of("url", Optional.of("  ws://localhost:8090 ")));
        assertEquals(Optional.of("ws://localhost:8090"), c.url());
        assertTrue(c.isActive());
        assertSame(c.url(), c.url(), "resolved once");
    }

    @Test
    void aBlankUrlWithoutDiscoveryLeavesTheClientInactive() {
        assertTrue(client(Map.of("url", Optional.of("  "))).url().isEmpty());
        assertFalse(client(Map.of()).isActive());
        assertFalse(client(Map.of("url", Optional.of("ws://localhost:1"), "enabled", false)).isActive());
    }

    @Test
    void theUrlIsDiscoveredThroughTheServiceResolution() {
        System.setProperty(PROBE_URL, "https://fermion.example/");
        final FermionClient c = client(Map.of("discover", true));
        assertEquals(Optional.of("https://fermion.example"), c.url());
        assertTrue(c.isActive());
    }

    @Test
    void aDiscoveryFailureLeavesTheClientInactive() {
        System.setProperty(PROBE_URL, "ftp://not-http");
        final FermionClient c = client(Map.of("discover", true));
        assertTrue(c.url().isEmpty());
        assertFalse(c.isActive());
    }

    @Test
    void aPlaintextUrlToAPublicHostIsRefused() {
        final FermionClient c = client(Map.of("url", Optional.of("ws://fermion.example")));
        assertTrue(c.url().isEmpty());
        assertFalse(c.isActive());
    }

    // --- connection ---

    @Test
    void connectingSendsAuthThenHelloThenKeepsTheConnection() throws Exception {
        final FermionClient c = active();
        assertFalse(c.isConnected());
        c.connect();
        assertTrue(c.isConnected());
        assertEquals(2, first.sent.size());
        assertInstanceOf(FermionMsg.Auth.class, mapper.readValue(first.sent.get(0), FermionMsg.class));
        final FermionMsg.Hello hello = assertInstanceOf(FermionMsg.Hello.class, mapper.readValue(first.sent.get(1), FermionMsg.class));
        assertEquals("orders", hello.appId());
        assertEquals(List.of("get", "baseUri ws://localhost:1", "pathParam nodeId=orders-0", "destroy"), connectorCalls);
        assertEquals(1, c.connectorsCreated());
        c.connect();
        assertEquals(1, c.connectorsCreated(), "already connected: no new attempt");
    }

    @Test
    void helloCarriesTheSelfUrlWithoutTrailingSlashes() {
        final FermionClient c = client(Map.of("url", Optional.of("ws://localhost:1"), "selfUrl", Optional.of("http://orders:8080//")));
        assertEquals("http://orders:8080/q/openapi", c.hello().openApiUrl());
        assertNull(client(Map.of("selfUrl", Optional.of("  "))).hello().openApiUrl());
        assertNull(client(Map.of()).hello().openApiUrl());
    }

    @Test
    void withoutATokenProviderOrTokenNothingIsAttempted() {
        final FermionClient none = active();
        none.tokens = instance(List.of());
        none.connect();
        assertEquals(0, none.connectorsCreated());

        final FermionClient c = active();
        for (final String blank : new String[]{null, "", "  "}) {
            token = blank;
            c.connect();
        }
        assertEquals(0, c.connectorsCreated());
        assertFalse(c.isConnected());
    }

    @Test
    void aRefusedConnectionIsLoggedAndRetriedLater() {
        next[0] = null;
        final FermionClient c = active();
        c.connect();
        assertFalse(c.isConnected());
        assertEquals(1, c.connectorsCreated());
        assertEquals("destroy", connectorCalls.get(connectorCalls.size() - 1), "the connector is always released");
        next[0] = first;
        c.connect();
        assertTrue(c.isConnected());
        assertEquals(2, c.connectorsCreated(), "a new connector for each attempt");
    }

    @Test
    void theStartupEventConnectsAnActiveClientAndOnlyThat() {
        final FermionClient c = active();
        c.onStart(null);
        assertTrue(c.isConnected());
        final FermionClient off = client(Map.of());
        off.onStart(null);
        assertFalse(off.isConnected());
    }

    // --- submitting workflows ---

    @Test
    void anInactiveClientCannotSubmit() {
        assertThrows(FermionClientException.class, () -> client(Map.of()).startWorkflow("w", "c", Map.of()));
    }

    @Test
    void aWorkflowIsSentWhenConnectedAndQueuedWhenNot() throws Exception {
        final FermionClient c = active();
        c.startWorkflow("w1", "c1", Map.of("k", 1));
        assertEquals(1, c.pendingCount());
        c.connect();
        assertEquals(0, c.pendingCount(), "replayed on connection");
        assertEquals(3, first.sent.size());
        assertEquals("w1", assertInstanceOf(FermionMsg.Start.class, mapper.readValue(first.sent.get(2), FermionMsg.class)).workflow());
        c.startWorkflow("w2", "c2", "tx", Map.of(), "acme");
        assertEquals(4, first.sent.size());
        assertEquals("acme", assertInstanceOf(FermionMsg.Start.class, mapper.readValue(first.sent.get(3), FermionMsg.class)).organization());
    }

    @Test
    void aSendThatFailsIsQueuedAndTheQueueIsBounded() {
        final FermionClient c = active();
        c.connect();
        first.sendFailures = 1;
        c.startWorkflow("w1", "c1", Map.of());
        assertEquals(1, c.pendingCount(), "the failed send is kept for the next connection");
        c.startWorkflow("w2", "c2", Map.of());
        assertEquals(2, c.pendingCount(), "while something waits, the order is kept: queued too");
        final FermionQueueFullException full = assertThrows(FermionQueueFullException.class, () -> c.startWorkflow("w3", "c3", Map.of()));
        assertNotNull(full.getMessage());
    }

    @Test
    void announcingTheUsageNeedsAConnectionAndSurvivesASendFailure() {
        final FermionClient c = active();
        c.announceUsage();
        assertTrue(first.sent.isEmpty(), "deferred to the next hello");
        c.connect();
        c.announceUsage();
        assertEquals(3, first.sent.size());
        first.sendFailures = 1;
        c.announceUsage();
        assertEquals(3, first.sent.size(), "the failure is logged, not thrown");
    }

    // --- callbacks ---

    @Test
    void workflowCallbacksAreCalledAndTheirFailuresContained() {
        final FermionClient c = active();
        c.fireWorkflowError("c1", "nobody listens");
        c.fireWorkflowDone(new FermionMsg.Done("c1", null, "w", Map.of()));
        final List<String> seen = new CopyOnWriteArrayList<>();
        c.onWorkflowError((id, message) -> seen.add(id + ":" + message));
        c.onWorkflowDone((id, payload) -> seen.add(id + "=" + payload));
        c.fireWorkflowError("c2", "refused");
        c.fireWorkflowDone(new FermionMsg.Done("c3", null, "w", Map.of("k", 1)));
        assertEquals(List.of("c2:refused", "c3={k=1}"), seen);
        c.onWorkflowError((id, message) -> {
            throw new IllegalStateException("sink broke");
        });
        c.onWorkflowDone((id, payload) -> {
            throw new IllegalStateException("sink broke");
        });
        c.fireWorkflowError("c4", "x");
        c.fireWorkflowDone(new FermionMsg.Done("c4", null, "w", Map.of()));
        c.onWorkflowDone(null);
        c.fireWorkflowDone(new FermionMsg.Done("c5", null, "w", Map.of()));
    }

    // --- losing and probing the connection ---

    @Test
    void aClosedConnectionIsForgottenOnlyWhenItIsTheCurrentOne() {
        final FermionClient c = active();
        c.connect();
        c.connectionLost(new PlainFermionFixtures.Link("another").connection());
        assertTrue(c.isConnected(), "another connection closing does not matter");
        c.connectionLost(null);
        assertTrue(c.isConnected());
        c.connectionLost(first.connection());
        assertFalse(c.isConnected());
        c.connectionLost(first.connection());
    }

    @Test
    void aClosedChannelIsNotConnected() {
        final FermionClient c = active();
        c.connect();
        first.open = false;
        assertFalse(c.isConnected());
    }

    @Test
    void theScheduledReconnectionDoesNothingWhenInactiveAndPingsWhenConnected() {
        client(Map.of()).reconnect();
        final FermionClient c = active();
        c.reconnect();
        assertTrue(c.isConnected(), "not connected: it connects");
        final int created = c.connectorsCreated();
        c.reconnect();
        assertEquals(created, c.connectorsCreated(), "a connection that answers its ping is left alone");
    }

    @Test
    void aStaleConnectionIsClosedAndReplaced() {
        final FermionClient c = active();
        c.connect();
        first.pingFails = true;
        final PlainFermionFixtures.Link second = new PlainFermionFixtures.Link("c-2");
        next[0] = second;
        c.reconnect();
        assertTrue(c.isConnected());
        assertEquals(2, c.connectorsCreated());
        assertEquals(2, second.sent.size(), "auth and hello on the new connection");
    }

    @Test
    void aStaleConnectionThatCannotBeClosedIsStillReplaced() {
        final FermionClient c = active();
        c.connect();
        first.pingFails = true;
        first.closeFails = true;
        final PlainFermionFixtures.Link second = new PlainFermionFixtures.Link("c-2");
        next[0] = second;
        c.reconnect();
        assertTrue(c.isConnected());
        assertEquals(2, second.sent.size());
    }
}
