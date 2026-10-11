package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.fermion.Language;
import io.gluonify.plasma.fermion.FermionMsg;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@QuarkusTest
class FermionClientTest {

    @Inject
    FermionClient client;

    @Inject
    ObjectMapper mapper;

    static void await(final String what, final BooleanSupplier condition) throws InterruptedException {
        final long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("timeout: " + what);
            }
            Thread.sleep(50);
        }
    }

    @BeforeEach
    void connected() throws InterruptedException {
        FakeFermionServer.accepting = true;
        await("client connected", () -> client.isConnected() && !FakeFermionServer.OPEN.isEmpty());
        Thread.sleep(300); // let the Auth and Hello of a fresh session land before the slate is cleared
        FakeFermionServer.FRAMES.clear();
        client.onWorkflowError(null);
    }

    private static <T extends FermionMsg> List<T> received(final Class<T> type) {
        return FakeFermionServer.FRAMES.stream().map(FakeFermionServer.Frame::msg).filter(type::isInstance).map(type::cast).toList();
    }

    private void dropAndWaitNewSession() throws InterruptedException {
        final int before = FakeFermionServer.sessions;
        FakeFermionServer.dropAll();
        await("new session", () -> FakeFermionServer.sessions > before && client.isConnected());
        await("hello of new session", () -> FakeFermionServer.FRAMES.stream().anyMatch(f -> f.msg() instanceof FermionMsg.Hello));
    }

    @Test
    void authenticatesThenAnnouncesItself() throws Exception {
        dropAndWaitNewSession();
        final List<FermionMsg> first = FakeFermionServer.FRAMES.stream().map(FakeFermionServer.Frame::msg).toList();
        final FermionMsg.Auth auth = assertInstanceOf(FermionMsg.Auth.class, first.get(0));
        assertEquals("test-token", auth.token());
        assertEquals(FermionMsg.PROTOCOL, auth.protocol());
        final FermionMsg.Hello hello = assertInstanceOf(FermionMsg.Hello.class, first.get(1));
        assertEquals("orders", hello.appId());
        assertEquals("3", hello.nodeId());
        assertEquals("orders-3", FakeFermionServer.lastNodeId);
        assertEquals("http://orders:8080/q/openapi", hello.openApiUrl());
        assertEquals(List.of("BOOM", "ECHO", "FAIL", "RELEASE", "SLOW"), hello.skills().stream().map(s -> s.id()).toList());
        final var echo = hello.skills().stream().filter(s -> s.id().equals("ECHO")).findFirst().orElseThrow();
        assertEquals(5, echo.translations().size());
        assertEquals(Language.FR, echo.translations().get(1).language());
        assertEquals("upper", echo.outputs().get(0).name());
        assertEquals("order-flow", hello.usedWorkflows().get(0).id());
    }

    @Test
    void executesCommandsAndAnswersWithResults() throws Exception {
        FakeFermionServer.send(mapper, new FermionMsg.Command("c1", "g1", "ECHO", Map.of("text", "abc"), "Task_Echo"));
        await("result", () -> !received(FermionMsg.Result.class).isEmpty());
        final FermionMsg.Result r = received(FermionMsg.Result.class).get(0);
        assertTrue(r.success());
        assertEquals("c1", r.correlationId());
        assertEquals("g1", r.globalTransactionId());
        assertEquals("ECHO", r.action());
        assertEquals("Task_Echo", r.stepId(), "the step id of the command is echoed back");
        assertEquals("ABC", r.payload().get("upper"));
        assertFalse(String.valueOf(r.payload().get("thread")).contains("vert.x-eventloop"), "handler must run off the event loop");
    }

    @Test
    void failuresCarryTheirErrorKind() throws Exception {
        FakeFermionServer.send(mapper, new FermionMsg.Command("c2", null, "FAIL", Map.of(), "Task_Fail"));
        FakeFermionServer.send(mapper, new FermionMsg.Command("c3", null, "BOOM", Map.of()));
        FakeFermionServer.send(mapper, new FermionMsg.Command("c4", null, "NOPE", Map.of()));
        FakeFermionServer.send(mapper, new FermionMsg.Command("c5", null, "ECHO", Map.of()));
        await("4 results", () -> received(FermionMsg.Result.class).size() == 4);
        final Map<String, FermionMsg.Result> byId = new java.util.HashMap<>();
        received(FermionMsg.Result.class).forEach(r -> byId.put(r.correlationId(), r));
        assertFalse(byId.get("c2").success());
        assertEquals("STORAGE_UNAVAILABLE", byId.get("c2").errorKind());
        assertEquals("disk is gone", byId.get("c2").error());
        assertEquals("Task_Fail", byId.get("c2").stepId());
        assertFalse(byId.get("c3").success());
        assertNull(byId.get("c3").errorKind());
        assertEquals("boom", byId.get("c3").error());
        assertEquals("NO_HANDLER", byId.get("c4").errorKind());
        assertEquals("MISSING_PARAMETER", byId.get("c5").errorKind());
    }

    @Test
    void aLongTaskDoesNotBlockTheOthers() throws Exception {
        FakeFermionServer.send(mapper, new FermionMsg.Command("s1", null, "SLOW", Map.of()));
        FakeFermionServer.send(mapper, new FermionMsg.Command("s2", null, "RELEASE", Map.of()));
        await("2 results", () -> received(FermionMsg.Result.class).size() == 2);
        final List<FermionMsg.Result> results = received(FermionMsg.Result.class);
        assertEquals(List.of("s2", "s1"), results.stream().map(FermionMsg.Result::correlationId).toList(), "RELEASE answers while SLOW is still running");
        assertEquals(Boolean.TRUE, results.get(1).payload().get("released"));
    }

    @Test
    void relaysWorkflowErrors() throws Exception {
        final List<String> errors = new CopyOnWriteArrayList<>();
        client.onWorkflowError((id, message) -> errors.add(id + ":" + message));
        FakeFermionServer.send(mapper, new FermionMsg.Error("c9", "workflow inactive"));
        await("error", () -> !errors.isEmpty());
        assertEquals(List.of("c9:workflow inactive"), errors);
    }

    @Test
    void startsAreSentWhenConnectedAndUsageCanBeReannounced() throws Exception {
        client.startWorkflow("order-flow", "o1", "g0", Map.of("n", 1));
        client.startWorkflow("order-flow", "o2", Map.of());
        client.announceUsage();
        await("frames", () -> received(FermionMsg.Start.class).size() == 2 && received(FermionMsg.Hello.class).size() == 1);
        assertEquals("g0", received(FermionMsg.Start.class).get(0).globalTransactionId());
        assertNull(received(FermionMsg.Start.class).get(1).globalTransactionId());
    }

    @Test
    void reconnectsWithAFreshConnectorAfterACut() throws Exception {
        final int connectors = client.connectorsCreated();
        dropAndWaitNewSession();
        assertTrue(client.connectorsCreated() > connectors);
        assertInstanceOf(FermionMsg.Auth.class, FakeFermionServer.FRAMES.get(0).msg());
        assertInstanceOf(FermionMsg.Hello.class, FakeFermionServer.FRAMES.get(1).msg());
    }

    @Test
    void queuesDuringAnOutageReplaysAfterAndOverflowsExplicitly() throws Exception {
        FakeFermionServer.accepting = false;
        FakeFermionServer.dropAll();
        await("client notices the cut", () -> !client.isConnected());
        client.startWorkflow("order-flow", "q1", Map.of());
        client.startWorkflow("order-flow", "q2", Map.of());
        client.startWorkflow("order-flow", "q3", Map.of());
        assertEquals(3, client.pendingCount());
        assertThrows(FermionQueueFullException.class, () -> client.startWorkflow("order-flow", "q4", Map.of()));
        assertEquals(3, client.pendingCount());

        FakeFermionServer.accepting = true;
        await("replay", () -> received(FermionMsg.Start.class).size() == 3);
        assertEquals(List.of("q1", "q2", "q3"), received(FermionMsg.Start.class).stream().map(FermionMsg.Start::correlationId).toList());
        final List<FermionMsg> order = FakeFermionServer.FRAMES.stream().map(FakeFermionServer.Frame::msg).toList();
        assertInstanceOf(FermionMsg.Auth.class, order.get(0));
        assertInstanceOf(FermionMsg.Hello.class, order.get(1));
        assertEquals(0, client.pendingCount());
    }
}
