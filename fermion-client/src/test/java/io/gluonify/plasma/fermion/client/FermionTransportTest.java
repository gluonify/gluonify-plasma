package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The transport rule of a node: TLS always, plaintext only toward the internal network.
 *
 * @author David CABILLIC
 */
class FermionTransportTest {

    @ParameterizedTest
    @ValueSource(strings = {"https://fermion.example.org", "WSS://fermion.example.org:443", "http://10.0.0.5:8506", "ws://127.0.0.1:9",
            "http://[::1]:9", "http://localhost:8080/", "http://app.localhost", "http://172.18.0.4:8080", "http://192.168.1.2",
            "http://169.254.1.2", "http://[fd00::5]:80"})
    void aSecureOrInternalUrlIsAccepted(final String url) {

        assertTrue(FermionTransport.acceptable(url), url);
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://fermion.example.org", "ws://fermion.lan:8080", "http://8.8.8.8", "http://[2001:db8::1]", "ftp://10.0.0.5",
            "http://", "not a url", "//10.0.0.5"})
    void aPlaintextUrlTowardAnotherHostIsRefused(final String url) {

        assertFalse(FermionTransport.acceptable(url), url);
    }
}
