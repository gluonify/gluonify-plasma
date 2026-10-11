package io.gluonify.plasma.testing;

import java.util.HashMap;
import java.util.Map;

/**
 * Faux services de Gluonify pour les tests d'une application, <b>activés explicitement</b> : {@link #start()} démarre un faux Crystallon en mémoire
 * (un serveur HTTP du JDK sur la boucle locale, port libre) et pose la surcharge que lit {@code GluonifyServices}
 * ({@code GLUONIFY_SERVICE_CRYSTALLON_URL} et {@code GLUONIFY_CRYSTALLON_TOKEN} en propriétés système) : {@code CrystallonClient.create()} et le
 * producteur CDI de {@code gluonify-plasma-quarkus} s'y adressent sans rien configurer. {@link #close()} arrête le serveur et rend les propriétés
 * d'avant.
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
 * <p>Une seule instance à la fois par processus (les propriétés système sont globales) ; un second {@code start()} avant {@code close()} lève une
 * {@link IllegalStateException}. Java 17, aucune dépendance.
 */
public final class GluonifyTestServices implements AutoCloseable {
    /** Jeton présenté par les clients pendant les tests (jamais un vrai jeton). */
    public static final String TOKEN = "gluonify-test-token";

    private static final Object LOCK = new Object();
    private static GluonifyTestServices active;

    private final FakeCrystallon crystallon;
    private final Map<String, String> previous = new HashMap<>();

    private GluonifyTestServices() {
        this.crystallon = new FakeCrystallon(TOKEN);
    }

    /** Démarre les faux services et pose la surcharge. @throws IllegalStateException si une instance est déjà active */
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

    /** Le faux Crystallon : rendus reçus, échecs programmés. */
    public FakeCrystallon crystallon() {
        return crystallon;
    }

    private void set(final String key, final String value) {
        previous.put(key, System.getProperty(key));
        System.setProperty(key, value);
    }

    /** Arrête les faux services et rend les propriétés système d'avant. */
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
