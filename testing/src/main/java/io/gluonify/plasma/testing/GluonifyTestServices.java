package io.gluonify.plasma.testing;

import java.util.HashMap;
import java.util.Map;

/**
 * Fake Gluonify services for an application's tests, <b>explicitly enabled</b>: {@link #start()} starts an in-memory fake Crystallon
 * (a JDK HTTP server on the loopback interface, free port) and sets the override read by {@code GluonifyServices}
 * ({@code GLUONIFY_SERVICE_CRYSTALLON_URL} and {@code GLUONIFY_CRYSTALLON_TOKEN} as system properties): {@code CrystallonClient.create()} and the
 * CDI producer of {@code gluonify-plasma-quarkus} talk to it with nothing to configure. {@link #close()} stops the server and restores the previous
 * properties.
 *
 * <pre>{@code
 * static GluonifyTestServices services;
 *
 * @BeforeAll static void start() { services = GluonifyTestServices.start(); }
 * @AfterAll  static void stop()  { services.close(); }
 *
 * @Test void invoice() {
 *     RenderedPdf pdf = CrystallonClient.create().byName("Gluonify invoice").render(payload);
 *     assertEquals("Gluonify invoice", services.crystallon().lastRender().template());
 * }
 * }</pre>
 *
 * <p>Only one instance at a time per process (system properties are global); a second {@code start()} before {@code close()} throws an
 * {@link IllegalStateException}. Java 17, no dependency.
 */
public final class GluonifyTestServices implements AutoCloseable {
    /** Token presented by the clients during tests (never a real token). */
    public static final String TOKEN = "gluonify-test-token";

    private static final Object LOCK = new Object();
    private static GluonifyTestServices active;

    private final FakeCrystallon crystallon;
    private final Map<String, String> previous = new HashMap<>();

    private GluonifyTestServices() {
        this.crystallon = new FakeCrystallon(TOKEN);
    }

    /** Starts the fake services and sets the override. @throws IllegalStateException if an instance is already active */
    public static GluonifyTestServices start() {
        synchronized (LOCK) {
            if (active != null) {
                throw new IllegalStateException("GluonifyTestServices is already started: close() the previous instance first");
            }
            final GluonifyTestServices s = new GluonifyTestServices();
            s.set("GLUONIFY_SERVICE_CRYSTALLON_URL", s.crystallon.url());
            s.set("GLUONIFY_CRYSTALLON_TOKEN", TOKEN);
            active = s;
            return s;
        }
    }

    /** The fake Crystallon: renders received, scheduled failures. */
    public FakeCrystallon crystallon() {
        return crystallon;
    }

    private void set(final String key, final String value) {
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** Stops the fake services and restores the previous system properties. */
    @Override
    public void close() {
        synchronized (LOCK) {
            if (active != this) {
                return;
            }
            for (final Map.Entry<String, String> e : previous.entrySet()) {
                if (e.getValue() == null) {
                    System.clearProperty(e.getKey());
                }
                else {
                    System.setProperty(e.getKey(), e.getValue());
                }
            }
            crystallon.close();
            active = null;
        }
    }
}
