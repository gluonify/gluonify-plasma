package io.gluonify.plasma.crystallon.client;

/** Quel Jackson le chemin de classes du test contient (réglé par l'exécution surefire : {@code crystallon.test.jackson}). */
final class JacksonClasspath {
    /** {@code both}, {@code 3}, {@code 2}, {@code 2-without-jsr310} ou {@code none}. */
    static final String EXPECTED = System.getProperty("crystallon.test.jackson", "both");

    private JacksonClasspath() {
    }

    static boolean jackson3() {
        return present("tools.jackson.databind.json.JsonMapper");
    }

    static boolean jackson2() {
        return present("com.fasterxml.jackson.databind.ObjectMapper");
    }

    static boolean jsr310() {
        return present("com.fasterxml.jackson.datatype.jsr310.JavaTimeModule");
    }

    static boolean present(final String className) {
        try {
            Class.forName(className);
            return true;
        }
        catch (final ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
