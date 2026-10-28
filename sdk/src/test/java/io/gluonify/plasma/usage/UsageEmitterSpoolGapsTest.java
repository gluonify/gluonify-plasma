package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The usage emitter against statuses and a real HTTP endpoint, and the durable spool on a real folder: retries, drops, bounds, restarts, damaged files. */
class UsageEmitterSpoolGapsTest {
    static final long T0 = 1_791_000_000_000L - (1_791_000_000_000L % 60_000);
    static final UsageRecord.Subject SHOP = new UsageRecord.Subject("acme", "shop", null, "service");

    @TempDir
    Path dir;

    HttpServer server;
    volatile int status = 202;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    final List<String> authorizations = new CopyOnWriteArrayList<>();
    final List<String> paths = new CopyOnWriteArrayList<>();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    String start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            authorizations.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            paths.add(ex.getRequestURI().getPath());
            ex.sendResponseHeaders(status, -1);
            ex.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    static final class Scripted implements UsageEmitter.Transport {
        final List<Integer> statuses = new ArrayList<>();
        final List<String> bodies = new ArrayList<>();
        boolean interrupt;
        boolean fail;

        @Override
        public int post(final String body) throws IOException, InterruptedException {
            if (interrupt) {
                throw new InterruptedException("stop");
            }
            if (fail) {
                throw new IOException("unreachable");
            }
            bodies.add(body);
            return statuses.isEmpty() ? 202 : statuses.remove(0);
        }
    }

    record Rig(AtomicLong clock, UsageMeter meter, UsageSpool spool, Scripted transport, UsageEmitter emitter) {
    }

    Rig rig(final Path spoolDir) {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(spoolDir, 100_000, 3_600_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 100_000, clock::get, spool);
        final Scripted transport = new Scripted();
        return new Rig(clock, meter, spool, transport, new UsageEmitter(meter, spool, () -> transport, clock::get));
    }

    static void use(final Rig r, final int apps) {
        for (int i = 0; i < apps; i++) {
            r.meter().add(new UsageRecord.Subject("acme", "app" + i, null, "service"), "rup.vfs.stored_bytes", null, 1);
        }
    }

    // --- statuses ---

    @Test
    void anAcknowledgedBatchLeavesTheSpoolAndIsCounted() {
        final Rig r = rig(null);
        use(r, 3);
        r.clock().set(T0 + 61_000);
        r.emitter().tick();
        assertEquals(3, r.emitter().sent());
        assertEquals(202, r.emitter().lastStatus());
        assertEquals(0, r.spool().size());
        assertSame(r.meter(), r.emitter().meter());
        assertSame(r.spool(), r.emitter().spool());
        assertTrue(r.transport().bodies.get(0).startsWith("{\"records\":["));
    }

    @Test
    void aBatchRefusedAsAWholeIsDroppedAndCounted() {
        for (final int refusal : new int[]{400, 413, 422}) {
            final Rig r = rig(null);
            use(r, 2);
            r.transport().statuses.add(refusal);
            r.clock().set(T0 + 61_000);
            r.emitter().tick();
            assertEquals(0, r.spool().size(), "status " + refusal);
            assertEquals(2, r.spool().dropped(), "status " + refusal);
            assertEquals(0, r.emitter().sent());
        }
    }

    @Test
    void anAnswerThatMeansLaterKeepsTheBatchAndBacksOffExponentiallyUpToTheMaximum() {
        final Rig r = rig(null);
        use(r, 1);
        r.clock().set(T0 + 61_000);
        long expectedBackoff = UsageEmitter.MIN_BACKOFF;
        for (final int code : new int[]{401, 403, 500, 503, 429, 500, 500, 500, 500, 500, 500, 500}) {
            r.transport().statuses.add(code);
        }
        r.emitter().tick();
        assertEquals(1, r.spool().size());
        assertEquals(401, r.emitter().lastStatus());
        int posts = r.transport().bodies.size();
        r.clock().addAndGet(expectedBackoff - 1);
        r.emitter().tick();
        assertEquals(posts, r.transport().bodies.size(), "still backing off");
        for (int round = 0; round < 8; round++) {
            r.clock().addAndGet(UsageEmitter.MAX_BACKOFF + 1);
            r.emitter().tick();
            assertEquals(1, r.spool().size());
        }
        assertTrue(r.transport().bodies.size() > posts);
        r.transport().statuses.clear();
        r.clock().addAndGet(UsageEmitter.MAX_BACKOFF + 1);
        r.emitter().tick();
        assertEquals(0, r.spool().size(), "once it answers, the backlog is sent");
        assertEquals(1, r.emitter().sent());
    }

    @Test
    void anUnreachableCalorimeterIsRetriedLaterAndReportedAsMinusOne() {
        final Rig r = rig(null);
        use(r, 1);
        r.transport().fail = true;
        r.clock().set(T0 + 61_000);
        r.emitter().tick();
        assertEquals(-1, r.emitter().lastStatus());
        assertEquals(1, r.spool().size());
    }

    @Test
    void anInterruptedSendStopsWithoutCountingAStatusAndKeepsTheInterruption() {
        final Rig r = rig(null);
        use(r, 1);
        r.transport().interrupt = true;
        r.clock().set(T0 + 61_000);
        try {
            r.emitter().tick();
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, r.emitter().lastStatus());
            assertEquals(1, r.spool().size());
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void noTransportOrNoAddressMeansNothingIsSentButEverythingIsKept() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(null, 1000, 3_600_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 100, clock::get, spool);
        meter.add(SHOP, "rup.vfs.stored_bytes", null, 1);
        clock.set(T0 + 61_000);
        new UsageEmitter(meter, spool, null, clock::get).tick();
        assertEquals(1, spool.size());
        new UsageEmitter(meter, spool, () -> null, clock::get).tick();
        assertEquals(1, spool.size());
    }

    @Test
    void aFailureInsideATickIsNeverAnExceptionTowardTheService() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(null, 1000, 3_600_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 100, clock::get, spool);
        final UsageEmitter emitter = new UsageEmitter(meter, spool, Scripted::new, () -> {
            throw new IllegalStateException("clock broke");
        });
        assertDoesNotThrow(emitter::tick);
        assertDoesNotThrow(emitter::close);
    }

    @Test
    void shuttingDownSendsTheCurrentWindowAndIgnoresTheBackoff() {
        final Rig r = rig(null);
        use(r, 2);
        r.transport().statuses.add(500);
        r.clock().set(T0 + 61_000);
        r.emitter().tick();
        use(r, 1);
        r.emitter().close();
        assertEquals(0, r.spool().size());
        assertEquals(3, r.emitter().sent());
    }

    @Test
    void aBigBacklogIsSentInBatchesOfAThousandAtMost() {
        final Rig r = rig(null);
        use(r, UsageEmitter.MAX_BATCH + 50);
        r.clock().set(T0 + 61_000);
        r.emitter().tick();
        assertEquals(2, r.transport().bodies.size());
        assertEquals(UsageEmitter.MAX_BATCH + 50, r.emitter().sent());
    }

    // --- HTTP transport ---

    @Test
    void theHttpTransportPostsToTheUsageRouteWithTheTokenReadOnEachSend() throws Exception {
        final String base = start();
        final Path tokenFile = dir.resolve("token");
        final UsageEmitter.Transport t = UsageEmitter.http(base + "///", tokenFile, Duration.ofSeconds(5));
        assertEquals(202, t.post("{\"records\":[]}"));
        assertEquals("null", authorizations.get(0), "no token file yet");
        Files.writeString(tokenFile, "  tok-1\n");
        t.post("{}");
        assertEquals("Bearer tok-1", authorizations.get(1));
        Files.writeString(tokenFile, "tok-2");
        t.post("{}");
        assertEquals("Bearer tok-2", authorizations.get(2), "a renewed token is picked up");
        Files.writeString(tokenFile, "  \n");
        t.post("{}");
        assertEquals("null", authorizations.get(3), "an empty token is no token");
        assertEquals("/v1/usage/records", paths.get(0));
        status = 503;
        assertEquals(503, t.post("{}"));
        assertEquals("{\"records\":[]}", bodies.get(0));
        status = 202;
        final int before = authorizations.size();
        assertEquals(202, UsageEmitter.http(base, null, Duration.ofSeconds(5)).post("{}"), "no token file configured");
        assertEquals("null", authorizations.get(before));
    }

    @Test
    void theTransportFollowsTheEnvironmentAndIsCachedWhileItDoesNotChange() throws Exception {
        final String base = start();
        final Map<String, String> env = new java.util.HashMap<>();
        final java.util.function.Supplier<UsageEmitter.Transport> supplier = UsageEmitter.fromEnvironment(env::get, Duration.ofSeconds(5));
        assertNull(supplier.get(), "no address");
        env.put("GLUONIFY_SERVICE_CALORIMETER_URL", " ");
        assertNull(supplier.get(), "a blank address is no address");
        env.put("GLUONIFY_SERVICE_CALORIMETER_URL", base);
        final UsageEmitter.Transport first = supplier.get();
        assertNotNull(first);
        assertSame(first, supplier.get());
        env.put("GLUONIFY_CALORIMETER_TOKEN_FILE", " ");
        final UsageEmitter.Transport blankFile = supplier.get();
        assertNotSame(first, blankFile, "the token file setting changed");
        final Path tokenFile = Files.writeString(dir.resolve("t"), "abc");
        env.put("GLUONIFY_CALORIMETER_TOKEN_FILE", tokenFile.toString());
        final UsageEmitter.Transport withFile = supplier.get();
        assertNotSame(blankFile, withFile);
        assertEquals(202, withFile.post("{}"));
        assertEquals("Bearer abc", authorizations.get(authorizations.size() - 1));
        assertSame(withFile, supplier.get());
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedFunctionFormOfTheEnvironmentTransportStillWorks() {
        final Map<String, String> env = Map.of("GLUONIFY_SERVICE_CALORIMETER_URL", "http://127.0.0.1:1");
        final java.util.function.Function<String, String> function = env::get;
        assertNotNull(UsageEmitter.fromEnvironment(function, Duration.ofSeconds(1)).get());
    }

    @Test
    void aBodyIsTheJsonArrayOfItsLines() {
        assertEquals("{\"records\":[]}", UsageEmitter.body(List.of()));
        assertEquals("{\"records\":[{\"a\":1},{\"b\":2}]}", UsageEmitter.body(List.of("{\"a\":1}", "{\"b\":2}")));
        assertEquals(List.of(), UsageEmitter.lines(List.of()));
    }

    // --- the spool ---

    @Test
    void aSpoolOnDiskSurvivesARestartWithoutGapsAndKeepsItsDropCount() {
        final Rig first = rig(dir);
        use(first, 3);
        first.clock().set(T0 + 61_000);
        first.emitter().tick(); // transport answers 202 only if scripted: nothing pending afterwards
        use(first, 0);
        first.transport().statuses.add(500);
        first.meter().add(SHOP, "rup.vfs.stored_bytes", null, 1);
        first.clock().set(T0 + 125_000);
        first.emitter().tick();
        assertEquals(1, first.spool().size());
        final long epoch = first.spool().epoch();

        final AtomicLong clock = new AtomicLong(T0 + 130_000);
        final UsageSpool second = new UsageSpool(dir, 100_000, 3_600_000, clock::get);
        assertEquals(epoch, second.epoch(), "the sequence continues in the same epoch");
        assertEquals(1, second.size());
        assertTrue(second.next() > 3, "no number is reused");
    }

    @Test
    void theOldestBatchesAreDroppedAndCountedWhenTheBoundsAreCrossed() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(dir, 3, 3_600_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 1000, clock::get, spool);
        for (int batch = 0; batch < 3; batch++) {
            for (int i = 0; i < 2; i++) {
                meter.add(new UsageRecord.Subject("acme", "b" + batch + "a" + i, null, "service"), "rup.vfs.stored_bytes", null, 1);
            }
            clock.addAndGet(60_000);
            spool.append(meter.drain());
        }
        assertTrue(spool.size() <= 3);
        assertEquals(6 - spool.size(), spool.dropped());
        assertEquals(String.valueOf(spool.dropped()), readFile("dropped").trim(), "persisted");

        clock.addAndGet(2 * 3_600_000L);
        spool.trim(clock.get());
        assertEquals(0, spool.size(), "everything is older than the maximum age");
        assertEquals(6, spool.dropped());
    }

    String readFile(final String name) {
        try {
            return Files.readString(dir.resolve(name));
        }
        catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anEmptyAppendDoesNothingAndADamagedSequenceStartsANewEpoch() throws Exception {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(dir, 10, 3_600_000, clock::get);
        spool.append(List.of());
        assertEquals(0, spool.size());
        Files.writeString(dir.resolve("seq"), "not numbers at all");
        clock.set(T0 + 5_000);
        final UsageSpool reopened = new UsageSpool(dir, 10, 3_600_000, clock::get);
        assertEquals(T0 + 5_000, reopened.epoch(), "a new epoch: no gap is looked for between two");
        assertEquals(1, reopened.next());
    }

    @Test
    void filesThatAreNotBatchesAreIgnoredAndAnUnreadableDropCountIsReset() throws Exception {
        Files.writeString(dir.resolve("b-notanumber.jsonl"), "{}");
        Files.writeString(dir.resolve("dropped"), "garbage");
        Files.writeString(dir.resolve("b-100-0-1.jsonl"), "{\"a\":1}\n\n{\"b\":2}\n");
        final UsageSpool spool = new UsageSpool(dir, 10, Long.MAX_VALUE / 2, () -> 200);
        final List<UsageSpool.Batch> pending = spool.pending();
        assertEquals(1, pending.size());
        assertEquals(List.of("{\"a\":1}", "{\"b\":2}"), pending.get(0).lines(), "blank lines are skipped");
        assertEquals(100, pending.get(0).at());
    }

    @Test
    void batchesAreSentOldestFirstAndEqualDatesFollowTheirSequence() throws Exception {
        Files.writeString(dir.resolve("b-100-5-1.jsonl"), "{\"n\":\"late\"}\n");
        Files.writeString(dir.resolve("b-100-2-1.jsonl"), "{\"n\":\"early\"}\n");
        Files.writeString(dir.resolve("b-50-9-1.jsonl"), "{\"n\":\"oldest\"}\n");
        final UsageSpool spool = new UsageSpool(dir, 10, Long.MAX_VALUE / 2, () -> 200);
        assertEquals(List.of("{\"n\":\"oldest\"}", "{\"n\":\"early\"}", "{\"n\":\"late\"}"), spool.pending().stream().map(b -> b.lines().get(0)).toList());
        final UsageSpool.Batch gone = spool.pending().get(0);
        spool.remove(gone);
        spool.remove(gone);
        assertEquals(2, spool.pending().size(), "removing twice is harmless");
    }

    @Test
    void aSpoolWithoutADirectoryKeepsBatchesInMemoryWithTheSameBounds() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(null, 2, 60_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 1000, clock::get, spool);
        for (int i = 0; i < 4; i++) {
            meter.add(new UsageRecord.Subject("acme", "a" + i, null, "service"), "rup.vfs.stored_bytes", null, 1);
        }
        clock.set(T0 + 60_000);
        spool.append(meter.drain());
        assertEquals(0, spool.size(), "the bound counts records, and the oldest batch (all four records) goes first");
        assertEquals(4, spool.dropped());
        assertEquals(5, spool.next(), "the four records of the drain took the numbers 1 to 4");
    }

    @Test
    void aFolderThatCannotBeWrittenKeepsTheBatchInMemory() throws Exception {
        final Path file = Files.writeString(dir.resolve("a-file"), "x");
        final AtomicLong clock = new AtomicLong(T0);
        final UsageSpool spool = new UsageSpool(file, 10, 3_600_000, clock::get);
        final UsageMeter meter = new UsageMeter(new UsageMeter.Identity("rup", "r1", "n1", "1.0", "PRD"), 10, clock::get, spool);
        meter.add(SHOP, "rup.vfs.stored_bytes", null, 1);
        clock.set(T0 + 60_000);
        spool.append(meter.drain());
        assertEquals(1, spool.size(), "kept in memory, never an exception");
    }
}
