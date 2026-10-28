package io.gluonify.plasma.fermion.client;

import static io.gluonify.plasma.fermion.client.PlainFermionFixtures.config;
import static io.gluonify.plasma.fermion.client.PlainFermionFixtures.instance;
import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.fermion.FermionMsg;
import io.quarkus.websockets.next.WebSocketClientConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The channel's client side without a server: frames in, answers out, heartbeats and progress events, cancellation, and the refusals of unusable frames. */
class FermionClientEndpointPlainGapsTest {
    final ObjectMapper mapper = JsonMapper.builder().build();
    final PlainFermionFixtures.Link link = new PlainFermionFixtures.Link("c-1");
    final List<SkillHandler> handlers = new ArrayList<>();
    final FermionClient client = new FermionClient();

    /** A handler that runs {@code body}. */
    static SkillHandler handler(final String action, final Body body) {
        return new SkillHandler() {
            @Override
            public String action() {
                return action;
            }

            @Override
            public Map<String, Object> handle(final TaskContext context) throws Exception {
                return body.run(context);
            }
        };
    }

    @FunctionalInterface
    interface Body {
        Map<String, Object> run(TaskContext context) throws Exception;
    }

    FermionClientEndpoint endpoint(final Duration heartbeat, final SkillHandler... skills) {
        final FermionClientEndpoint e = new FermionClientEndpoint();
        final SkillHandlerRegistry registry = new SkillHandlerRegistry();
        registry.handlers = instance(List.of(skills));
        registry.index();
        e.registry = registry;
        e.client = client;
        e.mapper = mapper;
        e.config = config(Map.of("heartbeatInterval", heartbeat));
        return e;
    }

    String command(final String action, final String stepId) throws Exception {
        return mapper.writeValueAsString(new FermionMsg.Command("c-1", "tx", action, Map.of("k", "v"), stepId));
    }

    FermionMsg.Result answer(final String json) throws Exception {
        return assertInstanceOf(FermionMsg.Result.class, mapper.readValue(json, FermionMsg.class));
    }

    // --- frames that are not commands ---

    @Test
    void anUnreadableFrameIsDroppedWithoutAnAnswer() {
        final FermionClientEndpoint e = endpoint(Duration.ZERO);
        assertNull(e.onMessage("not json", link.connection()));
        assertNull(e.onMessage("{\"type\":\"unknown-type\"}", link.connection()));
    }

    @Test
    void errorsAndDoneFramesReachTheCallbacks() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO);
        final List<String> seen = new CopyOnWriteArrayList<>();
        client.onWorkflowError((id, message) -> seen.add("error " + id + " " + message));
        client.onWorkflowDone((id, payload) -> seen.add("done " + id + " " + payload));
        assertNull(e.onMessage(mapper.writeValueAsString(new FermionMsg.Error("c-9", "workflow missing")), link.connection()));
        assertNull(e.onMessage(mapper.writeValueAsString(new FermionMsg.Done("c-9", null, "w", Map.of("n", 1))), link.connection()));
        assertEquals(List.of("error c-9 workflow missing", "done c-9 {n=1}"), seen);
    }

    @Test
    void framesThatOnlyANodeSendsAreIgnored() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO);
        for (final FermionMsg unexpected : List.<FermionMsg>of(new FermionMsg.Auth("t"), new FermionMsg.Start("w", "c", null, Map.of(), null),
                FermionMsg.Result.ok(new FermionMsg.Command("c", null, "a", Map.of(), null), Map.of()), FermionMsg.Progress.of(new FermionMsg.Command("c", null, "a", Map.of(), null), null))) {
            assertNull(e.onMessage(mapper.writeValueAsString(unexpected), link.connection()), unexpected.getClass().getSimpleName());
        }
    }

    // --- commands ---

    @Test
    void aCommandIsRunAndAnsweredWithItsVariables() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO, handler("ECHO", c -> Map.of("echo", c.getString("k"))));
        final FermionMsg.Result r = answer(e.onMessage(command("ECHO", "Task_1"), link.connection()));
        assertTrue(r.success());
        assertEquals(Map.of("echo", "v"), r.payload());
        assertEquals("Task_1", r.stepId());
    }

    @Test
    void aCommandWithoutHandlerFailsWithNoHandler() throws Exception {
        final FermionMsg.Result r = answer(endpoint(Duration.ZERO).onMessage(command("UNKNOWN", null), link.connection()));
        assertFalse(r.success());
        assertEquals("NO_HANDLER", r.errorKind());
        assertTrue(r.error().contains("No handler for action UNKNOWN"), r.error());
    }

    @Test
    void failuresCarryTheirKindOrTheirTypeWhenTheyHaveNoMessage() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO,
                handler("KIND", c -> {
                    throw new SkillFailureException("STORAGE", "disk is gone");
                }),
                handler("PLAIN", c -> {
                    throw new java.io.IOException("plain failure");
                }),
                handler("SILENT", c -> {
                    throw new IllegalStateException();
                }));
        final FermionMsg.Result kind = answer(e.onMessage(command("KIND", null), link.connection()));
        assertEquals("STORAGE", kind.errorKind());
        assertEquals("disk is gone", kind.error());
        final FermionMsg.Result plain = answer(e.onMessage(command("PLAIN", null), link.connection()));
        assertNull(plain.errorKind());
        assertEquals("plain failure", plain.error());
        final FermionMsg.Result silent = answer(e.onMessage(command("SILENT", null), link.connection()));
        assertEquals("java.lang.IllegalStateException", silent.error(), "no message: the exception type tells what happened");
    }

    // --- progress events and heartbeats ---

    @Test
    void progressEventsAreSentWhileTheConnectionIsOpenAndDroppedOtherwise() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO, handler("WORK", c -> {
            c.progress(Map.of("phase", "one"));
            link.open = false;
            c.progress(Map.of("phase", "dropped: closed"));
            link.open = true;
            link.sendFailures = 1;
            c.progress(Map.of("phase", "dropped: send failed"));
            c.progress(Map.of("phase", "two"));
            return Map.of();
        }));
        answer(e.onMessage(command("WORK", null), link.connection()));
        final List<String> phases = link.sent.stream().map(s -> s.contains("\"one\"") ? "one" : s.contains("\"two\"") ? "two" : "other").toList();
        assertEquals(List.of("one", "two"), phases);
    }

    @Test
    void progressWithoutAConnectionIsDropped() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO, handler("WORK", c -> {
            c.progress(Map.of("phase", "nowhere"));
            return Map.of("done", true);
        }));
        assertTrue(answer(e.onMessage(command("WORK", null), null)).success());
    }

    @Test
    void aRunningTaskSendsHeartbeatsEvenWhenOneSendFails() throws Exception {
        final CountDownLatch beats = new CountDownLatch(2);
        link.sendFailures = 1;
        link.onSend = s -> beats.countDown();
        final FermionClientEndpoint e = endpoint(Duration.ofMillis(20), handler("LONG", c -> {
            assertTrue(beats.await(30, TimeUnit.SECONDS), "two heartbeats arrive while the task runs");
            return Map.of();
        }));
        assertTrue(answer(e.onMessage(command("LONG", null), link.connection())).success());
        assertTrue(link.sent.stream().allMatch(s -> s.contains("progress")), link.sent.toString());
    }

    @Test
    void noHeartbeatIsScheduledForAnInvalidIntervalOrWithoutConnection() throws Exception {
        for (final Duration every : new Duration[]{Duration.ZERO, Duration.ofSeconds(-1)}) {
            final FermionClientEndpoint e = endpoint(every, handler("QUICK", c -> Map.of()));
            assertTrue(answer(e.onMessage(command("QUICK", null), link.connection())).success());
        }
        final FermionClientEndpoint noConnection = endpoint(Duration.ofMillis(10), handler("QUICK", c -> Map.of()));
        assertTrue(answer(noConnection.onMessage(command("QUICK", null), null)).success());
        assertTrue(link.sent.isEmpty());
    }

    // --- cancellation ---

    @Test
    void aCancelOfATaskThatDoesNotRunHereIsAcknowledgedAsInterrupted() throws Exception {
        final FermionClientEndpoint e = endpoint(Duration.ZERO);
        final FermionMsg.Result byInstance = answer(e.onMessage(mapper.writeValueAsString(new FermionMsg.Cancel("c-1", null, "A", "deleted", false)), link.connection()));
        assertFalse(byInstance.success());
        assertEquals(FermionMsg.Cancel.INTERRUPTED, byInstance.errorKind());
        assertEquals("deleted", byInstance.error());
        final FermionMsg.Result byStep = answer(e.onMessage(mapper.writeValueAsString(new FermionMsg.Cancel("c-1", "Task_9", "A", "gone", true)), link.connection()));
        assertEquals("Task_9", byStep.stepId());
    }

    @Test
    void aCancelReachesTheRunningHandlerByStepOrByInstanceAndAFailingHandlerIsContained() throws Exception {
        final CountDownLatch running = new CountDownLatch(1);
        final CountDownLatch cancelled = new CountDownLatch(2);
        final SkillHandler blocking = new SkillHandler() {
            @Override
            public String action() {
                return "BLOCK";
            }

            @Override
            public Map<String, Object> handle(final TaskContext context) throws Exception {
                running.countDown();
                assertTrue(cancelled.await(30, TimeUnit.SECONDS));
                return Map.of();
            }

            @Override
            public void onCancel(final TaskContext context, final FermionMsg.Cancel cancel) {
                cancelled.countDown();
                if (cancelled.getCount() == 0) {
                    throw new IllegalStateException("handler broke while cancelling");
                }
            }
        };
        final FermionClientEndpoint e = endpoint(Duration.ZERO, blocking);
        final Thread task = new Thread(() -> {
            try {
                e.onMessage(command("BLOCK", "Task_B"), link.connection());
            }
            catch (final Exception ex) {
                throw new IllegalStateException(ex);
            }
        });
        task.start();
        assertTrue(running.await(30, TimeUnit.SECONDS));
        assertNull(e.onMessage(mapper.writeValueAsString(new FermionMsg.Cancel("c-1", "Task_B", "BLOCK", "stop", true)), link.connection()), "passed to the handler: no answer of its own");
        assertNull(e.onMessage(mapper.writeValueAsString(new FermionMsg.Cancel("c-1", null, "BLOCK", "stop", false)), link.connection()), "found by instance; its failure is only logged");
        task.join(30_000);
        assertFalse(task.isAlive());
    }

    // --- the channel's own events ---

    @Test
    void closingTheChannelTellsTheClientAndAnErrorIsOnlyLogged() {
        final FermionClientEndpoint e = endpoint(Duration.ZERO);
        final WebSocketClientConnection closed = link.connection();
        assertDoesNotThrow(() -> e.onClose(closed));
        final IllegalStateException error = new IllegalStateException("socket error");
        assertDoesNotThrow(() -> e.onError(error));
    }
}
