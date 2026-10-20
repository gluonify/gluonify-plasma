package io.gluonify.plasma.fermion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * The extensions E1 to E4 of the Fermion protocol: the shared fixtures ({@code fermion/*.json}, read byte for byte by the Python twin), the
 * round trip of the new records, and the masking of the new fields by a node that does not know them.
 *
 * @author David CABILLIC
 */
class FermionContractTest {

    private static final String RUN = "AGENT_RUN_L0_EDGE";

    private final JsonMapper json = JsonMapper.builder().build();

    private static String fixture(final String name) throws IOException {

        try (InputStream in = FermionContractTest.class.getResourceAsStream("/fermion/" + name + ".json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"auth", "hello", "hello-plain", "start", "command", "result", "result-interrupted", "progress", "progress-heartbeat",
            "done", "cancel", "error"})
    void everyFixtureIsWrittenBackByteForByte(final String name) throws IOException {

        final String text = fixture(name);
        final FermionMsg msg = json.readValue(text, FermionMsg.class);
        assertEquals(text, json.writeValueAsString(msg) + "\n", name);
    }

    @Test
    void doneCarriesTheFinalStateOfTheWorkflow() throws IOException {

        final FermionMsg.Done done = assertInstanceOf(FermionMsg.Done.class, json.readValue(fixture("done"), FermionMsg.class));
        assertEquals("q-1", done.correlationId());
        assertEquals("ask-question", done.workflow());
        assertEquals("8080", done.payload().get("answer"));
        assertTrue(new FermionMsg.Done("c", null, "w", null).payload().isEmpty());
        assertFalse(json.writeValueAsString(new FermionMsg.Done("c", null, "w", null)).contains("globalTransactionId"));
    }

    @Test
    void cancelSaysWhetherTheWorkIsResumable() throws IOException {

        final FermionMsg.Cancel cancel = assertInstanceOf(FermionMsg.Cancel.class, json.readValue(fixture("cancel"), FermionMsg.class));
        assertTrue(cancel.resumable());
        assertEquals("Task_Run", cancel.stepId());
        assertEquals("interrupted", FermionMsg.Cancel.INTERRUPTED);
        final FermionMsg.Result ack = assertInstanceOf(FermionMsg.Result.class, json.readValue(fixture("result-interrupted"), FermionMsg.class));
        assertEquals(FermionMsg.Cancel.INTERRUPTED, ack.errorKind());
    }

    @Test
    void progressKeepsItsFormerConstructorsAndDropsEmptyData() {

        final FermionMsg.Command command = new FermionMsg.Command("c", "g", RUN, Map.of(), "Task_Run");
        assertNull(FermionMsg.Progress.of(command, "beat").data());
        assertNull(FermionMsg.Progress.of(command, null, Map.of()).data(), "an empty event is a plain heartbeat");
        assertEquals(Map.of("phase", "gate"), FermionMsg.Progress.of(command, null, Map.of("phase", "gate")).data());
        assertNull(new FermionMsg.Progress("c", "g", RUN, "s", "m").data());
        assertFalse(json.writeValueAsString(FermionMsg.Progress.of(command, null)).contains("data"));
    }

    @Test
    void helloWithoutCapacityKeepsItsFormerJson() {

        final FermionMsg.Hello plain = new FermionMsg.Hello("a", "n", null, null, null);
        assertNull(plain.capacity());
        assertNull(new FermionMsg.Hello("a", "n", null, null, null, new NodeCapacity(null, null)).capacity(), "an empty capacity is no capacity");
        assertEquals("{\"t\":\"hello\",\"appId\":\"a\",\"nodeId\":\"n\",\"skills\":[],\"usedWorkflows\":[]}", json.writeValueAsString(plain));
    }

    @Test
    void skillRequirementsAreSortedAndOptional() {

        final SkillDescriptor skill = new SkillDescriptor(RUN, List.of(), List.of(), List.of(), "PT12H", Map.of("model", 1, "heavy", 1));
        assertEquals(List.of("heavy", "model"), List.copyOf(skill.requires().keySet()));
        assertEquals(Map.of("heavy", 1, "model", 1), skill.requirements());
        final SkillDescriptor none = new SkillDescriptor(RUN, List.of(), List.of(), List.of(), null, Map.of());
        assertNull(none.requires());
        assertTrue(none.requirements().isEmpty());
        assertFalse(json.writeValueAsString(none).contains("requires"));
    }

    @Test
    void anEarlierNodeMasksTheNewFieldsAndRefusesOnlyTheNewFrames() throws IOException {

        final EarlierMsg progress = json.readValue(fixture("progress"), EarlierMsg.class);
        assertEquals("gate", assertInstanceOf(EarlierMsg.Progress.class, progress).message());
        final EarlierMsg hello = json.readValue(fixture("hello"), EarlierMsg.class);
        assertEquals("superposition", assertInstanceOf(EarlierMsg.Hello.class, hello).appId());
        final String done = fixture("done");
        assertThrows(JacksonException.class, () -> json.readValue(done, EarlierMsg.class), "an earlier node logs a read error and goes on");
    }

    /** The protocol as a node built before E1 to E4 reads it. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
    @JsonSubTypes({@JsonSubTypes.Type(value = EarlierMsg.Hello.class, name = "hello"), @JsonSubTypes.Type(value = EarlierMsg.Progress.class, name = "progress")})
    sealed interface EarlierMsg {

        /**
         * The former announcement.
         *
         * @param appId  application
         * @param nodeId node
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Hello(String appId, String nodeId) implements EarlierMsg {
        }

        /**
         * The former heartbeat.
         *
         * @param correlationId instance
         * @param message       free text
         */
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Progress(String correlationId, String message) implements EarlierMsg {
        }
    }

    @Test
    void capacityParsesItsConfigurationForm() {

        final NodeCapacity capacity = NodeCapacity.of(Map.of("heavy", 1, "claude", 3), List.of("23:30-07:00", "sat,Sun 00:00-00:00"), null);
        assertEquals(List.of("claude", "heavy"), List.copyOf(capacity.slots().keySet()));
        assertEquals("23:30-07:00", capacity.closed().get(0).toString());
        assertEquals("SAT,SUN 00:00-00:00", capacity.closed().get(1).toString());
        assertEquals(NodeCapacity.Window.DEFAULT_ZONE, capacity.closed().get(0).zone());
        assertFalse(capacity.isEmpty());
        assertTrue(new NodeCapacity(Map.of(), List.of()).isEmpty());
        assertFalse(json.writeValueAsString(capacity).contains("empty"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"23:30", "7-8", "25:00-07:00", "XYZ 01:00-02:00", "23:30-07:00 extra"})
    void capacityRefusesAMalformedWindow(final String window) {

        final List<String> windows = List.of(window);
        final Map<String, Integer> slots = Map.of();
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.of(slots, windows, null), window);
    }

    @Test
    void capacityRefusesANegativeSlotAndAnUnknownZone() {

        final Map<String, Integer> negative = Map.of("heavy", -1);
        final List<String> none = List.of();
        final List<String> night = List.of("23:00-01:00");
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.of(negative, none, null));
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.of(Map.of(), night, "Mars/Olympus"));
        assertThrows(IllegalArgumentException.class, () -> NodeCapacity.Window.parse(null, null));
    }

    @Test
    void aWindowCrossingMidnightClosesBothEvenings() {

        final NodeCapacity.Window night = NodeCapacity.Window.parse("23:30-07:00", "UTC");
        assertTrue(night.contains(Instant.parse("2026-10-20T23:30:00Z")));
        assertTrue(night.contains(Instant.parse("2026-10-21T06:59:00Z")));
        assertFalse(night.contains(Instant.parse("2026-10-21T07:00:00Z")));
        assertFalse(night.contains(Instant.parse("2026-10-21T12:00:00Z")));
        assertFalse(night.contains(Instant.parse("2026-10-20T23:29:00Z")));
    }

    @Test
    void aWindowRestrictedToDaysStartsOnlyOnThem() {

        // 2026-10-26 is a Monday: the bench night starts at 00:00 that day.
        final NodeCapacity.Window bench = NodeCapacity.Window.parse("MON 00:00-06:00", "UTC");
        assertTrue(bench.contains(Instant.parse("2026-10-26T03:00:00Z")));
        assertFalse(bench.contains(Instant.parse("2026-10-27T03:00:00Z")));
        final NodeCapacity.Window weekendNight = NodeCapacity.Window.parse("SUN 22:00-02:00", "UTC");
        assertTrue(weekendNight.contains(Instant.parse("2026-10-26T01:00:00Z")), "the window started on Sunday evening");
        assertFalse(weekendNight.contains(Instant.parse("2026-10-27T01:00:00Z")));
        final NodeCapacity.Window wholeSaturday = NodeCapacity.Window.parse("SAT 00:00-00:00", "UTC");
        assertTrue(wholeSaturday.contains(Instant.parse("2026-10-24T12:00:00Z")));
        assertFalse(wholeSaturday.contains(Instant.parse("2026-10-25T12:00:00Z")));
    }

    @Test
    void aWindowUsesItsZone() {

        final NodeCapacity paris = NodeCapacity.of(Map.of(), List.of("23:30-07:00"), null);
        // 22:00 UTC is 00:00 in Paris on 2026-10-20 (summer time until 2026-10-25).
        assertTrue(paris.closedAt(Instant.parse("2026-10-20T22:00:00Z")));
        assertFalse(paris.closedAt(Instant.parse("2026-10-20T20:00:00Z")));
    }
}
