package io.gluonify.plasma.store;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.store.StoreCapability.Cap;
import org.junit.jupiter.api.Test;

class StoreCapabilityTest {
    static final String SHA = "ab".repeat(32);

    @Test
    void signeEtVerifie() {
        String t = StoreCapability.sign("cle", new Cap("put", SHA, 1234, 2000, "shop"));
        Cap c = StoreCapability.verify("cle", t, 1000);
        assertEquals(new Cap("put", SHA, 1234, 2000, "shop"), c);
    }

    @Test
    void refus() {
        String t = StoreCapability.sign("cle", new Cap("get", SHA, 0, 2000, "shop"));
        assertNull(StoreCapability.verify("autre-cle", t, 1000), "mauvaise clé");
        assertNull(StoreCapability.verify("cle", t, 2000), "échu");
        String[] p = t.split("\\.");
        String forged = p[0] + "." + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("get|x|0|9999999999|shop".getBytes()) + "." + p[2];
        assertNull(StoreCapability.verify("cle", forged, 1000), "contenu modifié");
        assertNull(StoreCapability.verify("cle", "cap.abc", 1000));
        assertNull(StoreCapability.verify("cle", "Bearer x", 1000));
        assertNull(StoreCapability.verify("cle", null, 1000));
        assertNull(StoreCapability.verify("cle", StoreCapability.sign("cle", new Cap("del", SHA, 0, 2000, "x")), 1000), "opération inconnue");
    }
}
