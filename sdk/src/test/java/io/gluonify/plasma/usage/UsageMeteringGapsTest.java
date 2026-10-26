package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The meter and the usage record: what is refused, merged, attributed and written, and the exact JSON of every optional part. */
class UsageMeteringGapsTest {
    static final long T0 = 1_791_000_000_000L - (1_791_000_000_000L % 60_000);
    static final UsageRecord.Subject SHOP = new UsageRecord.Subject("acme", "shop", "6f1c0000-0000-4000-8000-000000000001", "service");

    static UsageMeter meter(final String service, final String env, final AtomicLong clock, final int maxKeys) {
        return new UsageMeter(new UsageMeter.Identity(service, "r1", "n1", "1.0", env), maxKeys, clock::get, UsageMeter.memorySeq(7));
    }

    static UsageMeter rup(final AtomicLong clock) {
        return meter("rup", "SBX", clock, 100);
    }

    // --- refusals and defaults ---

    @Test
    void theEnvironmentDefaultsToPrdWhenAbsentOrBlank() {
        for (final String env : new String[]{null, "", "  "}) {
            final AtomicLong clock = new AtomicLong(T0);
            final UsageMeter m = meter("rup", env, clock, 10);
            assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1));
            clock.set(T0 + 60_000);
            assertEquals("PRD", m.drain().get(0).env());
        }
        assertEquals("rup", rup(new AtomicLong()).service());
    }

    @Test
    void whatCannotBeMeteredIsRefusedAndCounted() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter m = rup(clock);
        assertFalse(m.add(SHOP, "no.such.unit", null, 1));
        assertFalse(m.add(SHOP, "strange.instance.cpu", null, 1), "a unit of another service");
        assertFalse(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "floppy"), 1));
        assertFalse(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), -1));
        assertFalse(m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), (BigDecimal) null));
        assertFalse(m.add(null, "rup.vfs.stored_bytes", Map.of("kind", "std"), 1));
        assertEquals(6, m.invalid());
        assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", null, 1), "no dimension is allowed");
        assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", Map.of(), 1));
        assertEquals(6, m.invalid());
    }

    @Test
    void aZeroQuantityIsAcceptedAndLeavesNoRecord() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter m = rup(clock);
        assertTrue(m.add(SHOP, "rup.vfs.stored_bytes", null, 0));
        clock.set(T0 + 120_000);
        assertEquals(List.of(), m.drain());
    }

    // --- keys, overflow, windows ---

    @Test
    void sameKeyAddsUpAndDifferentWindowsStaySeparate() {
        final AtomicLong clock = new AtomicLong(T0 + 5);
        final UsageMeter m = rup(clock);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), new BigDecimal("0.5"));
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), new BigDecimal("0.25"));
        clock.set(T0 + 61_000);
        m.add(SHOP, "rup.vfs.stored_bytes", Map.of("kind", "std"), new BigDecimal("1"));
        clock.set(T0 + 200_000);
        final List<UsageRecord> out = m.drain();
        assertEquals(2, out.size());
        assertEquals(new BigDecimal("0.75"), out.get(0).quantity());
        assertEquals(T0, out.get(0).windowStart());
        assertEquals(T0 + 60_000, out.get(1).windowStart());
        assertEquals(1, out.get(0).seq());
        assertEquals(2, out.get(1).seq());
        assertEquals(7, out.get(0).emitter().epoch());
        assertEquals(List.of(), m.drain(), "drained windows are gone");
    }

    @Test
    void aWindowThatIsNotClosedYetIsKeptUntilShutdown() {
        final AtomicLong clock = new AtomicLong(T0 + 5);
        final UsageMeter m = rup(clock);
        m.add(SHOP, "rup.vfs.stored_bytes", null, 3);
        assertEquals(List.of(), m.drain());
        assertEquals(List.of(), m.drain(T0 + 59_999));
        assertEquals(1, m.drain(Long.MAX_VALUE).size(), "at shutdown the current window is counted too");
    }

    @Test
    void keysBeyondTheLimitAreMergedIntoOverflowPerOrganization() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter m = meter("rup", null, clock, 2);
        for (int i = 0; i < 5; i++) {
            m.add(new UsageRecord.Subject("acme", "app" + i, null, "service"), "rup.vfs.stored_bytes", null, 1);
        }
        assertEquals(3, m.overflowed());
        clock.set(T0 + 60_000);
        final List<UsageRecord> out = m.drain();
        assertEquals(3, out.size());
        final UsageRecord overflow = out.stream().filter(r -> "_overflow".equals(r.subject().app())).findFirst().orElseThrow();
        assertEquals(new BigDecimal("3"), overflow.quantity());
        assertEquals("acme", overflow.subject().org());
    }

    @Test
    void anInternalUnitOrAnInternalFlagIsRecordedAsInternalAndOnBehalfOfIsKept() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter bup = meter("bup", null, clock, 10);
        assertTrue(bup.add(T0, SHOP, new UsageMeter.Measure("bup.jobs.seconds", Map.of("kind", "render"), BigDecimal.TEN), new UsageMeter.Attribution(false, null)));
        final UsageMeter rup = rup(clock);
        assertTrue(rup.add(T0, SHOP, new UsageMeter.Measure("rup.vfs.stored_bytes", null, BigDecimal.ONE), new UsageMeter.Attribution(true, "other-org")));
        assertTrue(rup.add(T0, SHOP, new UsageMeter.Measure("rup.vfs.stored_bytes", null, BigDecimal.ONE), new UsageMeter.Attribution(false, "other-org")));
        assertTrue(rup.add(T0, SHOP, new UsageMeter.Measure("rup.vfs.stored_bytes", null, BigDecimal.ONE), new UsageMeter.Attribution(false, null)));
        clock.set(T0 + 60_000);
        assertTrue(bup.drain().get(0).internal(), "the unit is internal");
        final List<UsageRecord> out = rup.drain();
        assertEquals(3, out.size(), "internal, on behalf of, and plain are three keys");
        assertEquals(1, out.stream().filter(UsageRecord::internal).count());
        assertEquals(2, out.stream().filter(r -> "other-org".equals(r.onBehalfOf())).count());
    }

    @Test
    void anIndicativeUnitIsNotBillable() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter strange = meter("strange", null, clock, 10);
        assertTrue(strange.add(SHOP, "strange.instance.memory_used", null, 5));
        assertTrue(strange.add(SHOP, "strange.instance.cpu", null, 5));
        clock.set(T0 + 60_000);
        final Map<String, String> confidence = new TreeMap<>();
        strange.drain().forEach(r -> confidence.put(r.unit(), r.confidence()));
        assertEquals("indicative", confidence.get("strange.instance.memory_used"));
        assertEquals("billable", confidence.get("strange.instance.cpu"));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedFormsStillMeter() {
        final AtomicLong clock = new AtomicLong(T0);
        final UsageMeter m = new UsageMeter("rup", "r1", "n1", "1.0", "PRD", 10, clock::get, UsageMeter.memorySeq(1));
        assertTrue(m.add(T0, SHOP, "rup.vfs.stored_bytes", null, BigDecimal.ONE, true, "o"));
        clock.set(T0 + 60_000);
        final UsageRecord r = m.drain().get(0);
        assertTrue(r.internal());
        assertEquals("o", r.onBehalfOf());
    }

    @Test
    void volumesAreConvertedRoundingDown() {
        assertEquals(new BigDecimal("1.000000000"), UsageMeter.gbHours(1L << 30, 3600));
        assertEquals(new BigDecimal("0.000000000"), UsageMeter.gbHours(1, 1));
        assertEquals(new BigDecimal("1.000000000"), UsageMeter.mbSeconds(1L << 20, 1));
        assertEquals(new BigDecimal("0.500000000"), UsageMeter.mbSeconds(1L << 19, 1));
        assertEquals(5L, UsageMeter.memorySeq(5).epoch());
        final UsageMeter.SeqSource seq = UsageMeter.memorySeq(5);
        assertEquals(1, seq.next());
        assertEquals(2, seq.next());
    }

    // --- the record ---

    @Test
    void theJsonOmitsWhatIsAbsentAndEscapesWhatMustBe() {
        final UsageRecord.Emitter bare = new UsageRecord.Emitter("rup", "r1", null, null, 9);
        final UsageRecord.Subject nobody = new UsageRecord.Subject(null, null, null, null);
        final UsageRecord minimal = new UsageRecord("id1", 1, bare, "PRD", T0, nobody, "rup.vfs.stored_bytes", null, new BigDecimal("1.50"), false, null, "billable");
        final String json = minimal.toJson();
        assertFalse(json.contains("\"node\""), json);
        assertFalse(json.contains("\"version\""), json);
        assertFalse(json.contains("\"app\""), json);
        assertFalse(json.contains("\"appUuid\""), json);
        assertFalse(json.contains("\"resource\""), json);
        assertFalse(json.contains("\"dims\""), json);
        assertFalse(json.contains("onBehalfOf"), json);
        assertTrue(json.contains("\"org\":\"_unassigned\""), json);
        assertTrue(json.contains("\"kind\":\"service\""), json);
        assertTrue(json.contains("\"quantity\":\"1.5\""), json);

        final UsageRecord full = new UsageRecord("id2", 2, new UsageRecord.Emitter("rup", "r1", "n1", "1.0", 9), "PRD", T0,
                new UsageRecord.Subject("acme", "shop", "uuid", "user", "rup:std/shop"), "rup.vfs.stored_bytes", new TreeMap<>(Map.of("kind", "std", "other", "x")), BigDecimal.ONE, true, "other", "indicative");
        final String fullJson = full.toJson();
        assertTrue(fullJson.contains("\"node\":\"n1\"") && fullJson.contains("\"version\":\"1.0\""), fullJson);
        assertTrue(fullJson.contains("\"app\":\"shop\"") && fullJson.contains("\"appUuid\":\"uuid\"") && fullJson.contains("\"resource\":\"rup:std/shop\""), fullJson);
        assertTrue(fullJson.contains("\"dims\":{\"kind\":\"std\",\"other\":\"x\"}"), fullJson);
        assertTrue(fullJson.contains("\"internal\":true") && fullJson.contains("\"onBehalfOf\":\"other\""), fullJson);
        assertFalse(fullJson.contains("\n"), "one line");
    }

    @Test
    void textIsQuotedWithEveryEscape() {
        assertEquals("null", UsageRecord.q(null));
        assertEquals("\"\"", UsageRecord.q(""));
        assertEquals("\"a\\\"b\\\\c\\nd\\re\\tf\\u0001g\\u001f\"", UsageRecord.q("a\"b\\c\nd\re\tf\u0001g\u001f"));
        assertEquals("\"café 中\"", UsageRecord.q("café 中"));
    }

    @Test
    void theKeyAndTheIdAreDeterministic() {
        final UsageRecord.Subject withResource = new UsageRecord.Subject("acme", null, null, "node", "gdown:base");
        assertEquals("PRD|acme|||node|gdown:base|u|a=1|b=2", UsageRecord.key("PRD", withResource, "u", Map.of("b", "2", "a", "1")));
        assertEquals("PRD|acme|shop|uuid|service||u", UsageRecord.key("PRD", new UsageRecord.Subject("acme", "shop", "uuid", null), "u", null));
        assertEquals(UsageRecord.id("s", "r", 1, "k"), UsageRecord.id("s", "r", 1, "k"));
        assertNotEquals(UsageRecord.id("s", "r", 1, "k"), UsageRecord.id("s", "r", 2, "k"));
        assertEquals(64, UsageRecord.id("s", "r", 1, "k").length());
    }

    @Test
    void theQuantityIsTruncatedToNineDecimalsInTheCustomersFavour() {
        final UsageRecord r = new UsageRecord("i", 1, new UsageRecord.Emitter("s", "r", null, null, 1), "PRD", 0, new UsageRecord.Subject("o", null, null, null), "u", null,
                new BigDecimal("0.1234567899"), false, null, "billable");
        assertEquals(new BigDecimal("0.123456789"), r.quantity());
        assertEquals(0, r.dims().size());
        assertEquals(new BigDecimal("100"), new UsageRecord("i", 1, r.emitter(), "PRD", 0, r.subject(), "u", null, new BigDecimal("1E+2"), false, null, "billable").quantity());
    }
}
