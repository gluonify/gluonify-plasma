package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.util.Map;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(DisabledClientTest.NoUrl.class)
class DisabledClientTest {

    public static class NoUrl implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // without URL or discovery (the test machine may be connected to a cluster through gluonify login)
            return Map.of("gluonify.fermion.client.url", "", "gluonify.fermion.client.discover", "false");
        }
    }

    @Inject
    FermionClient client;

    @Test
    void inactiveWithoutUrl() throws Exception {
        final int sessions = FakeFermionServer.sessions;
        Thread.sleep(2500);
        assertFalse(client.isActive());
        assertFalse(client.isConnected());
        assertEquals(sessions, FakeFermionServer.sessions);
        assertThrows(FermionClientException.class, () -> client.startWorkflow("w", "c", Map.of()));
    }
}
