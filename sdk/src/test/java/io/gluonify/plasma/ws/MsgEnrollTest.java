package io.gluonify.plasma.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The agent's feature list in {@link Msg.Enroll}: additive both ways on the wire, the former constructors kept.
 *
 * @author David CABILLIC
 */
class MsgEnrollTest {

    /** Same reading rule as the control-plane's channel (Quarkus' mapper, {@code quarkus.jackson.fail-on-unknown-properties=false}): unknown properties are ignored. */
    private static final JsonMapper LENIENT = JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

    private static final String TOKEN = "join-1";

    private static final String ADDRESS = "10.0.0.5";

    private static final String ARCH = "arm64";

    private static final String ROLE = "storage";

    private static final String OLD_ENROLL = "{\"t\":\"enroll\",\"token\":\"join-1\",\"address\":\"10.0.0.5\",\"memoryMb\":512,\"arch\":\"arm64\",\"role\":\"storage\"}";

    /**
     * The message family as a control-plane built on the previous SDK knows it: an enrolment without features.
     *
     * @author David CABILLIC
     */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "t")
    @JsonSubTypes(@JsonSubTypes.Type(value = OldEnroll.class, name = "enroll"))
    sealed interface OldMsg permits OldEnroll {
    }

    /**
     * The enrolment of the previous SDK (five components).
     *
     * @param token the single-use enrolment token
     * @param address the node's address
     * @param memoryMb memory of the node, in MiB
     * @param arch architecture of the node
     * @param role role of the node
     * @author David CABILLIC
     */
    record OldEnroll(String token, String address, int memoryMb, String arch, String role) implements OldMsg {
    }

    @Test
    void featuresRoundTripUnderTheEnrollType() {

        final Msg sent = new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES, Msg.AGENT_FEATURE_RAFT_PAIR_FILES));
        final String json = LENIENT.writeValueAsString(sent);
        assertTrue(json.contains("\"t\":\"enroll\""), json);
        assertTrue(json.contains("\"features\":[\"bolt-pair-files\",\"raft-pair-files\"]"), json);
        final Msg.Enroll back = assertInstanceOf(Msg.Enroll.class, LENIENT.readValue(json, Msg.class));
        assertEquals(sent, back);
        assertTrue(back.supports(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
        assertTrue(back.supports(Msg.AGENT_FEATURE_RAFT_PAIR_FILES));
        assertFalse(back.supports(Msg.AGENT_FEATURE_FRESH_STATE));
    }

    @Test
    void anEnrollFromAnOlderAgentHasNoFeatures() {

        final Msg.Enroll old = assertInstanceOf(Msg.Enroll.class, LENIENT.readValue(OLD_ENROLL, Msg.class));
        assertEquals(new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE), old);
        assertEquals(List.of(), old.features());
        assertFalse(old.supports(Msg.AGENT_FEATURE_BOLT_PAIR_FILES));
        final String explicitNull = OLD_ENROLL.replace("}", ",\"features\":null}");
        assertEquals(List.of(), assertInstanceOf(Msg.Enroll.class, LENIENT.readValue(explicitNull, Msg.class)).features());
    }

    @Test
    void theDefaultMapperReadsAnOlderEnrollToo() {

        final Msg.Enroll old = assertInstanceOf(Msg.Enroll.class, JsonMapper.builder().build().readValue(OLD_ENROLL, Msg.class));
        assertEquals(List.of(), old.features(), "an absent component is not an unknown property: even a strict mapper reads it as empty");
    }

    @Test
    void anOlderControlPlaneReadsTheEnrollAndIgnoresTheFeatures() {

        final String json = LENIENT.writeValueAsString(new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES, "later")));
        final OldEnroll old = assertInstanceOf(OldEnroll.class, LENIENT.readValue(json, OldMsg.class));
        assertEquals(new OldEnroll(TOKEN, ADDRESS, 512, ARCH, ROLE), old);
    }

    @Test
    void aStrictReaderOfTheOlderShapeWouldRefuseTheField() {

        final String json = JsonMapper.builder().build().writeValueAsString(new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES)));
        final JsonMapper strict = JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
        assertThrows(RuntimeException.class, () -> strict.readValue(json, OldMsg.class), "why the control-plane's channel must keep ignoring unknown properties");
    }

    @Test
    void theCompatibilityConstructorsAnnounceNothing() {

        final Msg.Enroll withRole = new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE);
        assertEquals(List.of(), withRole.features());
        assertEquals(new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, null), withRole);
        final Msg.Enroll withoutRole = new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH);
        assertNull(withoutRole.role());
        assertEquals(List.of(), withoutRole.features());
        assertEquals(new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, null, List.of()), withoutRole);
        assertNotEquals(withRole, new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, List.of(Msg.AGENT_FEATURE_BOLT_PAIR_FILES)));
    }

    @Test
    void theFeatureListIsACopyThatCannotChange() {

        final List<String> given = new ArrayList<>(List.of(Msg.AGENT_FEATURE_RAFT_PAIR_FILES));
        final Msg.Enroll enroll = new Msg.Enroll(TOKEN, ADDRESS, 512, ARCH, ROLE, given);
        given.clear();
        assertTrue(enroll.supports(Msg.AGENT_FEATURE_RAFT_PAIR_FILES), "a later change of the caller's list does not reach the message");
        final List<String> features = enroll.features();
        assertThrows(UnsupportedOperationException.class, features::clear);
    }

    @Test
    void theSharedFeatureNamesKeepTheirWireValues() {

        assertEquals("enroll-features", Msg.FEATURE_ENROLL_FEATURES, "the name gluonify-higgs will announce in Enrolled");
        assertEquals("raft-pair-files", Msg.AGENT_FEATURE_RAFT_PAIR_FILES, "the literal of strange BoltPair and higgs BoltPairFiles");
        final Msg.Enrolled enrolled = assertInstanceOf(Msg.Enrolled.class,
                LENIENT.readValue(LENIENT.writeValueAsString(new Msg.Enrolled("n1", "s", List.of(Msg.FEATURE_ENROLL_FEATURES))), Msg.class));
        assertTrue(enrolled.features().contains(Msg.FEATURE_ENROLL_FEATURES));
    }
}
