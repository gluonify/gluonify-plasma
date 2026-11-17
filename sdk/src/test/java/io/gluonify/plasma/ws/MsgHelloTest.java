package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent's feature list in {@link Msg.Hello} (GLU-119): additive both ways on the wire.
 *
 * @author David CABILLIC
 */
class MsgHelloTest {

    /** Same reading rule as the control-plane's channel (Quarkus' mapper): unknown properties are ignored. */
    private static final JsonMapper LENIENT = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    /**
     * A hello as an agent that knows nothing of features would have sent it, read by an older control-plane.
     *
     * @param node identifier of the node
     * @param secret the node's secret
     * @author David CABILLIC
     */
    record OldHello(String node, String secret) {
    }

    @Test
    void featuresRoundTripUnderTheHelloType() {

        final Msg sent = new Msg.Hello("n1", "s", List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
        final String json = LENIENT.writeValueAsString(sent);
        assertTrue(json.contains("\"t\":\"hello\""), json);
        assertTrue(json.contains("\"features\":[\"bolt-pair-files\"]"), json);
        final Msg.Hello back = assertInstanceOf(Msg.Hello.class, LENIENT.readValue(json, Msg.class));
        assertEquals(sent, back);
        assertTrue(back.supports(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
    }

    @Test
    void aHelloFromAnOlderAgentHasNoFeatures() {

        final Msg.Hello old = assertInstanceOf(Msg.Hello.class, LENIENT.readValue("{\"t\":\"hello\",\"node\":\"n1\",\"secret\":\"s\"}", Msg.class));
        assertEquals("n1", old.node());
        assertEquals(List.of(), old.features());
        assertFalse(old.supports(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
        final Msg.Hello explicitNull = assertInstanceOf(Msg.Hello.class, LENIENT.readValue("{\"t\":\"hello\",\"node\":\"n1\",\"secret\":\"s\",\"features\":null}", Msg.class));
        assertEquals(List.of(), explicitNull.features());
    }

    @Test
    void anOlderControlPlaneReadsTheHelloAndIgnoresTheFeatures() {

        final String json = LENIENT.writeValueAsString(new Msg.Hello("n1", "s", List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES, "later")));
        final OldHello old = LENIENT.readValue(json.replace("\"t\":\"hello\",", ""), OldHello.class);
        assertEquals(new OldHello("n1", "s"), old);
    }

    @Test
    void theCompatibilityConstructorAnnouncesNothing() {

        final Msg.Hello hello = new Msg.Hello("n1", "s");
        assertEquals(List.of(), hello.features());
        assertEquals(new Msg.Hello("n1", "s", null), hello);
    }

    @Test
    void theFeatureListIsACopyThatCannotChange() {

        final List<String> given = new ArrayList<>(List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
        final Msg.Hello hello = new Msg.Hello("n1", "s", given);
        given.clear();
        assertTrue(hello.supports(Msg.AGENT_FEATURE_BOLT_PAIR_FILES), "a later change of the caller's list does not reach the message");
        final List<String> features = hello.features();
        assertThrows(UnsupportedOperationException.class, features::clear);
    }
}
