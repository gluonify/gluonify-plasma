package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class UsageEmitterTest {
    static final long T0 = 1_791_000_000_000L - (1_791_000_000_000L % 60_000); // une minute pleine
    static final UsageRecord.Subject SHOP = new UsageRecord.Subject("acme", "shop", "6f1c0000-0000-4000-8000-000000000001", "service");
    final ObjectMapper json = new ObjectMapper();

    static UsageMeter meter(AtomicLong clock, UsageMeter.SeqSource seqs) {
        return new UsageMeter("rup", "fs", "n1", "0.7.0", "PRD", 20_000, clock::get, seqs);
    }

    /** Transport de test : rend le statut demandé et garde les corps reçus. */
    static final class Fake implements UsageEmitter.Transport {
        int status = 202;
        final List<String> bodies = new ArrayList<>();

        @Override
        public int post(String body) {
            if (status < 0) throw new IllegalStateException("down");
            bodies.add(body);
            return status;
        }
    }

    @Test
    void recordShapeMatchesTheCalorimeterFormat() throws Exception {
        AtomicLong clock = new AtomicLong(T0 + 1_000);
        UsageMeter m = meter(clock, UsageMeter.memorySeq(42));
        assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), new BigDecimal("0.5")));
        assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), new BigDecimal("0.25")));
        assertTrue(m.drain().isEmpty(), "la fenêtre en cours n'est pas close");
        clock.set(T0 + 60_000);
        List<UsageRecord> out = m.drain();
        assertEquals(1, out.size());
        JsonNode n = json.readTree(out.get(0).toJson());
        assertEquals(1, n.get("v").asInt());
        assertEquals(64, n.get("id").asString().length());
        assertEquals(1, n.get("seq").asLong());
        assertEquals("rup", n.at("/emitter/service").asString());
        assertEquals("fs", n.at("/emitter/replica").asString());
        assertEquals(42, n.at("/emitter/epoch").asLong());
        assertEquals("PRD", n.get("env").asString());
        assertEquals(java.time.Instant.ofEpochMilli(T0).toString(), n.at("/window/start").asString());
        assertEquals(60, n.at("/window/seconds").asInt());
        assertEquals("acme", n.at("/subject/org").asString());
        assertEquals("shop", n.at("/subject/app").asString());
        assertEquals("std", n.at("/dims/kind").asString());
        assertEquals("0.75", n.get("quantity").asString());
        assertEquals("billable", n.get("confidence").asString());
        assertFalse(n.get("internal").asBoolean());
    }

    @Test
    void idsAreStableAndSeqIncreases() {
        AtomicLong clock = new AtomicLong(T0);
        UsageMeter a = meter(clock, UsageMeter.memorySeq(1));
        UsageMeter b = meter(clock, UsageMeter.memorySeq(2)); // autre nœud, même réplique logique : même id
        a.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        b.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        a.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "tmp"), 1);
        clock.set(T0 + 60_000);
        List<UsageRecord> ra = a.drain(), rb = b.drain();
        assertEquals(ra.get(0).id(), rb.get(0).id());
        assertNotEquals(ra.get(0).id(), ra.get(1).id());
        assertEquals(List.of(1L, 2L), List.of(ra.get(0).seq(), ra.get(1).seq()));
    }

    @Test
    void foreignOrUnknownUnitsAndDimsAreRefused() {
        AtomicLong clock = new AtomicLong(T0);
        UsageMeter m = meter(clock, UsageMeter.memorySeq(1));
        assertFalse(m.add(SHOP, "bup.build.seconds", Map.of(), 1), "unité d'un autre service");
        assertFalse(m.add(SHOP, "rup.nothing", Map.of(), 1));
        assertFalse(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "nas"), 1));
        assertFalse(m.add(SHOP, "rup.vfs.stored_bytes", Map.of(), BigDecimal.valueOf(-1)));
        assertEquals(4, m.invalid());
    }

    @Test
    void keysBeyondTheCapAreMergedIntoOverflow() {
        AtomicLong clock = new AtomicLong(T0);
        UsageMeter m = new UsageMeter("rup", "fs", "n1", null, "PRD", 2, clock::get, UsageMeter.memorySeq(1));
        for (int i = 0; i < 5; i++) m.add(new UsageRecord.Subject("acme", "app" + i, null, "service"), "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        clock.set(T0 + 60_000);
        List<UsageRecord> out = m.drain();
        assertEquals(3, out.size());
        UsageRecord o = out.stream().filter(r -> UsageMeter.OVERFLOW.equals(r.subject().app())).findFirst().orElseThrow();
        assertEquals(0, new BigDecimal("3").compareTo(o.quantity()));
        assertEquals(3, m.overflowed());
    }

    @Test
    void quantitiesAreConverted() {
        assertEquals(0, BigDecimal.ONE.compareTo(UsageMeter.gbHours(1L << 30, 3600)));
        assertEquals(0, new BigDecimal("300").compareTo(UsageMeter.mbSeconds(1L << 20, 300)));
    }

    @Test
    void spoolKeepsBatchesWhileTheCalorimeterIsDownThenReplaysThem(@TempDir Path dir) throws Exception {
        AtomicLong clock = new AtomicLong(T0);
        UsageSpool spool = new UsageSpool(dir, 10_000, 86_400_000L, clock::get);
        UsageMeter m = meter(clock, spool);
        Fake down = new Fake();
        down.status = -1;
        UsageEmitter e = new UsageEmitter(m, spool, () -> down, clock::get);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        clock.set(T0 + 60_000);
        e.tick();
        assertEquals(1, spool.size(), "gardé sur disque");
        assertEquals(-1, e.lastStatus());

        // redémarrage du service : le spool et la suite sont relus
        UsageSpool again = new UsageSpool(dir, 10_000, 86_400_000L, clock::get);
        assertEquals(1, again.size());
        assertEquals(spool.epoch(), again.epoch());
        assertEquals(2, again.next(), "la suite continue sans trou");

        Fake up = new Fake();
        UsageEmitter e2 = new UsageEmitter(meter(clock, again), again, () -> up, clock::get);
        clock.addAndGet(1_000);
        e2.tick();
        assertEquals(1, up.bodies.size());
        JsonNode body = json.readTree(up.bodies.get(0));
        assertEquals(1, body.get("records").size());
        assertEquals(0, again.size());
        assertEquals(1, e2.sent());
    }

    @Test
    void backoffAfterAFailure(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(T0);
        UsageSpool spool = new UsageSpool(dir, 10_000, 86_400_000L, clock::get);
        UsageMeter m = meter(clock, spool);
        Fake t = new Fake();
        t.status = 503;
        UsageEmitter e = new UsageEmitter(m, spool, () -> t, clock::get);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        clock.set(T0 + 60_000);
        e.tick();
        assertEquals(1, t.bodies.size());
        clock.addAndGet(1_000);
        e.tick();
        assertEquals(1, t.bodies.size(), "pas de nouvel essai avant 5 s");
        t.status = 202;
        clock.addAndGet(5_000);
        e.tick();
        assertEquals(2, t.bodies.size());
        assertEquals(0, spool.size());
    }

    @Test
    void boundedSpoolDropsTheOldestAndCountsThem(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(T0);
        UsageSpool spool = new UsageSpool(dir, 2, 86_400_000L, clock::get);
        UsageMeter m = meter(clock, spool);
        UsageEmitter e = new UsageEmitter(m, spool, () -> null, clock::get); // aucun calorimètre configuré
        for (int w = 0; w < 4; w++) {
            m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
            clock.addAndGet(60_000);
            e.tick();
        }
        assertEquals(2, spool.size());
        assertEquals(2, spool.dropped());
        assertEquals(2, new UsageSpool(dir, 2, 86_400_000L, clock::get).dropped(), "compteur persisté");
        // l'âge aussi
        clock.addAndGet(2 * 86_400_000L);
        spool.trim(clock.get());
        assertEquals(0, spool.size());
        assertEquals(4, spool.dropped());
    }

    @Test
    void aBatchRefusedAsAWholeIsDroppedAndCounted(@TempDir Path dir) {
        AtomicLong clock = new AtomicLong(T0);
        UsageSpool spool = new UsageSpool(dir, 100, 86_400_000L, clock::get);
        UsageMeter m = meter(clock, spool);
        Fake t = new Fake();
        t.status = 400;
        UsageEmitter e = new UsageEmitter(m, spool, () -> t, clock::get);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        clock.set(T0 + 60_000);
        e.tick();
        assertEquals(0, spool.size());
        assertEquals(1, spool.dropped());
    }

    @Test
    void closeFlushesTheCurrentWindow() {
        AtomicLong clock = new AtomicLong(T0 + 5_000);
        UsageSpool spool = new UsageSpool(null, 100, 86_400_000L, clock::get);
        UsageMeter m = meter(clock, spool);
        Fake t = new Fake();
        UsageEmitter e = new UsageEmitter(m, spool, () -> t, clock::get);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1);
        e.close();
        assertEquals(1, t.bodies.size());
    }
}
