package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.ws.Msg;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code stateful} flag of a specification and of a deployment, and the {@code stateful} / {@code initialize} marks of a placed replica (stray cluster of
 * 2026-11-17): false and absent are one value, kept by the copies, additive on the wire both ways.
 *
 * @author David CABILLIC
 */
class StatefulFlagTest {

    private static final Map<String, String> ART = Map.of("amd64", "https://b/a#sha256=" + "a".repeat(64));

    /** Same reading rule as an agent's channel (Quarkus' mapper): unknown properties are ignored. */
    private static final JsonMapper LENIENT = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static AppSpec spec(final Boolean stateful) {

        return new AppSpec("shop", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true, stateful);
    }

    private static Instance replica() {

        return new Instance("shop", 1, "r/shop:1", 8080, 20001, 64, null, 3L, 0, false, Map.of("A", "b"), List.of("S"), List.of("gdown"), List.of(9100), List.of(),
                null, null, null, "acme", "u-1");
    }

    /**
     * A replica as an older agent knows it: no stateful mark.
     *
     * @param app   application
     * @param index replica index
     * @author David CABILLIC
     */
    record OldInstance(String app, int index) {
    }

    @Test
    void specificationNormalizesAndKeepsTheFlag() {

        assertTrue(spec(true).statefulEnabled());
        assertNull(spec(false).stateful(), "false is stored as absent");
        assertEquals(spec(false), spec(null), "stable equality of the stored specification");
        final AppSpec s = spec(true);
        assertTrue(s.withProvenance(null).withOrganization("acme").withDistributedQuotaMb(null).withArtifactLevel("amd64:v2").withPythonVersion(null).withMcp(true)
                .withTls(false).statefulEnabled(), "kept by every copy");
        assertFalse(s.withStateful(false).statefulEnabled());
        assertTrue(s.withStateful(false).withStateful(true).tlsEnabled(), "the copy keeps the other flags");
        assertNull(new AppSpec("a", null, 1, 8080, 64, null, ART, null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, true).stateful(), "compatibility constructor: stateless");
    }

    @Test
    void statefulStateLivesOnDisk() {

        assertEquals(0, spec(true).scratchMb(), "absent work space: the state folder on disk");
        assertEquals(AppSpec.DEFAULT_SCRATCH_MB, spec(null).scratchMb(), "stateless default unchanged");
        final AppSpec inMemory = spec(null);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> inMemory.withStateful(true));
        assertTrue(e.getMessage().contains("scratchMb"), e.getMessage());
    }

    @Test
    void deploymentCarriesTheFlagToTheSpecification() {

        final var req = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, true);
        assertTrue(req.toSpec("shop").statefulEnabled());
        final var old = new DeployRequest(null, null, null, null, null, ART, null, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, true);
        assertNull(old.stateful(), "compatibility constructor: stateless");
        assertFalse(old.toSpec("shop").statefulEnabled());
        assertTrue(old.toSpec("shop").tlsEnabled());
    }

    @Test
    void specificationJson() throws Exception {

        final var m = JsonMapper.builder().build();
        assertNull(m.readValue("{\"name\":\"a\",\"image\":\"r/a:1\"}", AppSpec.class).stateful(), "old specification (Raft snapshot): stateless");
        assertTrue(m.readValue(m.writeValueAsString(spec(true)), AppSpec.class).statefulEnabled());
        assertEquals(Boolean.TRUE, m.readValue("{\"image\":\"r/a:1\",\"stateful\":true}", DeployRequest.class).stateful());
    }

    @Test
    void replicaMarks() {

        final Instance plain = replica();
        assertNull(plain.stateful(), "compatibility constructor: no mark");
        assertNull(plain.initialize());
        assertFalse(plain.requiresExistingState());
        final Instance kept = plain.withState(true, false);
        assertTrue(kept.requiresExistingState(), "stateful, not its first start");
        assertEquals(plain.env(), kept.env(), "the rest of the replica is kept");
        assertEquals("acme", kept.organization());
        final Instance first = plain.withState(true, true);
        assertFalse(first.requiresExistingState(), "first placement: its state may be created");
        assertEquals(Boolean.TRUE, first.initialize());
        final Instance stateless = plain.withState(false, true);
        assertNull(stateless.stateful());
        assertNull(stateless.initialize(), "initialize only goes with stateful");
        assertEquals(plain, stateless, "false and absent are one value");
    }

    @Test
    void replicaWireIsAdditive() throws Exception {

        final String plain = LENIENT.writeValueAsString(replica());
        assertFalse(plain.contains("stateful") || plain.contains("initialize"), "absent marks are not written: " + plain);
        final String marked = LENIENT.writeValueAsString(replica().withState(true, true));
        final Instance back = LENIENT.readValue(marked, Instance.class);
        assertTrue(back.stateful() && back.initialize());
        assertEquals("shop", LENIENT.readValue(marked, OldInstance.class).app(), "an older agent ignores the marks");
        assertNull(LENIENT.readValue(plain, Instance.class).stateful(), "a replica from an older control-plane has no mark");
        assertEquals("stateful-instances", Msg.AGENT_FEATURE_STATEFUL_INSTANCES);
    }
}
