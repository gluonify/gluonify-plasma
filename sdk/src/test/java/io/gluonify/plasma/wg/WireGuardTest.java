package io.gluonify.plasma.wg;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class WireGuardTest {
    static final String KEY = "A".repeat(43) + "=";

    @Test void adressesDuMaillage() {
        assertEquals("10.77.0.1", Overlay.address(1));
        assertEquals("10.77.0.255", Overlay.address(255));
        assertEquals("10.77.1.0", Overlay.address(256));
        assertEquals("10.77.255.253", Overlay.address(65_533));
        assertThrows(IllegalStateException.class, () -> Overlay.address(0));
        assertThrows(IllegalStateException.class, () -> Overlay.address(65_534));
        assertTrue(Overlay.contains("10.77.3.4"));
        assertFalse(Overlay.contains("10.200.0.4"));
        assertFalse(Overlay.contains(null));
    }

    @Test void clesPubliques() {
        assertTrue(Overlay.validKey(KEY));
        assertFalse(Overlay.validKey("courte"));
        assertFalse(Overlay.validKey(null));
        assertFalse(Overlay.validKey("A".repeat(44)));
    }

    @Test void creationDeLInterface() {
        var c = WireGuard.createCommands("10.77.0.2", "/var/lib/gluonify/wg.key", 51820);
        assertEquals(List.of("ip", "link", "add", "wg0", "type", "wireguard"), c.get(0));
        assertEquals(List.of("wg", "set", "wg0", "private-key", "/var/lib/gluonify/wg.key", "listen-port", "51820"), c.get(1));
        assertEquals(List.of("ip", "addr", "add", "10.77.0.2/16", "dev", "wg0"), c.get(2));
        assertEquals(List.of("ip", "link", "set", "wg0", "mtu", "1380", "up"), c.get(3));
    }

    @Test void unPairNeRouteQueSonAdresse() {
        var p = new WireGuard.Peer("n-1", KEY, "192.0.2.10:51820", "10.77.0.3");
        assertEquals(List.of("wg", "set", "wg0", "peer", KEY, "allowed-ips", "10.77.0.3/32", "persistent-keepalive", "25", "endpoint", "192.0.2.10:51820"), WireGuard.peerCommand(p));
        assertFalse(WireGuard.peerCommand(new WireGuard.Peer("n-2", KEY, null, "10.77.0.4")).contains("endpoint"), "pair sans point d'accès connu : il se présentera lui-même");
    }
}
