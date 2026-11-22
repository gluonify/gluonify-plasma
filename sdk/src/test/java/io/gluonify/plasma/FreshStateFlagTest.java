package io.gluonify.plasma;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.ws.Msg;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@code freshState} identity of a placed replica (a gDown member under a new identity on a node that still holds an old state folder of the same name):
 * only with {@code stateful} and {@code initialize}, blank and absent are one value, kept by the copies, additive on the wire both ways.
 *
 * @author David CABILLIC
 */
class FreshStateFlagTest {

    private static final String FRESH_STATE = "freshState";

    private static final String NEW_ID = "gdown-2-2";

    /** Same reading rule as an agent's channel (Quarkus' mapper): unknown properties are ignored. */
    private static final JsonMapper LENIENT = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static Instance replica() {

        return new Instance("gdown", 1, "r/gdown:1", 8080, 20001, 64, null, 3L, 0, false, Map.of("A", "b"), List.of("S"), List.of(), List.of(9100), List.of(),
                null, null, null, "acme", "u-1");
    }

    /**
     * A replica as an agent that knows the stateful marks but not the fresh-state one reads it.
     *
     * @param app        application
     * @param index      replica index
     * @param stateful   stateful mark
     * @param initialize first-start mark
     * @author David CABILLIC
     */
    record StatefulOnlyInstance(String app, int index, Boolean stateful, Boolean initialize) {
    }

    @Test
    void theIdentityOnlyGoesWithTheFirstStartOfAStatefulReplica() {

        final Instance first = replica().withState(true, true).withFreshState(NEW_ID);
        assertTrue(first.requiresFreshState());
        assertEquals(NEW_ID, first.freshState());
        assertFalse(first.requiresExistingState(), "a fresh start never needs the old folder");
        assertNull(replica().withFreshState(NEW_ID).freshState(), "stateless: nothing to set aside");
        assertNull(replica().withState(true, false).withFreshState(NEW_ID).freshState(), "a restart keeps its folder");
        assertNull(first.withState(true, false).freshState(), "dropped with initialize");
        assertNull(first.withState(false, true).freshState(), "dropped with stateful");
    }

    @Test
    void blankAndAbsentAreOneValue() {

        final Instance first = replica().withState(true, true);
        assertNull(first.withFreshState(" ").freshState());
        assertEquals(first, first.withFreshState(NEW_ID).withFreshState(null), "stable equality of the desired state");
        assertFalse(first.withFreshState("").requiresFreshState());
        assertEquals(NEW_ID, first.withFreshState(" " + NEW_ID + "\n").freshState(), "stripped");
        assertNull(replica().freshState(), "compatibility constructor: no identity");
    }

    @Test
    void theCopiesKeepTheReplica() {

        final Instance fresh = replica().withState(true, true).withFreshState(NEW_ID);
        assertEquals(replica().env(), fresh.env());
        assertEquals("acme", fresh.organization());
        assertEquals("gdown-1", fresh.name());
        assertEquals(NEW_ID, fresh.withState(true, true).freshState(), "withState keeps the identity while it still applies");
    }

    @Test
    void theStatefulConstructorIsKept() {

        final Instance marked = new Instance("gdown", 1, "r/gdown:1", 8080, 20001, 64, null, 3L, 0, false, Map.of("A", "b"), List.of("S"), List.of(), List.of(9100),
                List.of(), null, null, null, "acme", "u-1", true, true);
        assertEquals(replica().withState(true, true), marked);
        assertNull(marked.freshState(), "compatibility constructor of 22 components: no identity");
    }

    @Test
    void theWireIsAdditive() throws Exception {

        final String plain = LENIENT.writeValueAsString(replica().withState(true, true));
        assertFalse(plain.contains(FRESH_STATE), "an absent identity is not written: " + plain);
        final String marked = LENIENT.writeValueAsString(replica().withState(true, true).withFreshState(NEW_ID));
        assertTrue(marked.contains("\"" + FRESH_STATE + "\":\"" + NEW_ID + "\""), marked);
        assertEquals(NEW_ID, LENIENT.readValue(marked, Instance.class).freshState());
        final StatefulOnlyInstance older = LENIENT.readValue(marked, StatefulOnlyInstance.class);
        assertEquals(new StatefulOnlyInstance("gdown", 1, true, true), older, "an older agent ignores the identity and reads the rest");
        assertFalse(LENIENT.readValue(plain, Instance.class).requiresFreshState(), "a replica from an older control-plane has no identity");
        final Instance strict = JsonMapper.builder().build().readValue(plain, Instance.class);
        assertNull(strict.freshState(), "a desired state written before the field (Raft snapshot, strict reader): no identity");
        assertEquals(replica().withState(true, true), strict, "and the rest read as before");
    }

    @Test
    void theAgentAnnouncesTheFeature() {

        assertEquals("fresh-state-folders", Msg.AGENT_FEATURE_FRESH_STATE);
        final Msg.Hello hello = new Msg.Hello("n-1", "s", List.of(Msg.AGENT_FEATURE_STATEFUL_INSTANCES, Msg.AGENT_FEATURE_FRESH_STATE));
        assertTrue(hello.supports(Msg.AGENT_FEATURE_FRESH_STATE));
        assertFalse(new Msg.Hello("n-1", "s").supports(Msg.AGENT_FEATURE_FRESH_STATE), "an older agent announces nothing");
    }
}
