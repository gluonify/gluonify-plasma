package io.gluonify.plasma.fermion.client;

import io.quarkus.logging.Log;
import java.text.MessageFormat;
import java.util.Optional;
import org.jboss.logging.Logger;

/**
 * How the client reads the configured gluonify-matrix version ({@code gluonify.fermion.client.matrix}) before it announces it in
 * {@code Hello.matrix}. Only a release version is announced: three dotted numbers of one to four digits, surrounding blanks ignored, in
 * the canonical form ({@code 01.2.3} is announced as {@code 1.2.3}, the form gluonify-fermion records and the name of the published
 * folder). This form is kept in step with {@code io.gluonify.fermion.defaults.BundleVersion} in gluonify-fermion (the bundle manifest's
 * release form) and with {@code _matrix_release} in gluonify-plasma-python's {@code gluonify/fermion.py}: a change to one is made to the
 * three. Anything else is announced as nothing, and one start-up line says why. The {@code Hello} record itself keeps any text it is
 * given: the contract does not change.
 *
 * @author David CABILLIC
 */
enum MatrixVersion {

    /**
     * A release version: announced in its canonical form.
     */
    RELEASE(Logger.Level.INFO, "fermion-client=matrix version={1} announced (configured {0})"),

    /**
     * Nothing configured: nothing announced, as before the field existed.
     */
    ABSENT(Logger.Level.DEBUG, "fermion-client=matrix none configured, nothing announced"),

    /**
     * A Maven placeholder left unfiltered ({@code @gluonify.matrix.version@}, {@code ${gluonify.matrix.version}}): expected in dev mode and
     * in an IDE, which read the resource from the source folder.
     */
    PLACEHOLDER(Logger.Level.INFO, "fermion-client=matrix value={0} is an unfiltered placeholder (dev mode reads the source resource), nothing announced"),

    /**
     * Any other text (a pre-release suffix, a range, two numbers, a number of five digits): a configuration mistake.
     */
    MALFORMED(Logger.Level.WARN, "fermion-client=matrix value={0} is not a release version x.y.z (one to four digits each), nothing announced");

    private static final int PARTS = 3;
    private static final int MAX_DIGITS = 4;
    private static final char MAVEN_DELIMITER = '@';
    private static final String PROPERTY_OPEN = "${";
    private static final String PROPERTY_CLOSE = "}";

    private final Logger.Level level;
    private final String line;

    MatrixVersion(final Logger.Level level, final String line) {

        this.level = level;
        this.line = line;
    }

    /**
     * Reads a configured value.
     *
     * @param configured the configured text, {@code null} when absent
     * @return what the value is
     */
    static MatrixVersion of(final String configured) {

        if (configured == null || configured.isBlank()) {
            return ABSENT;
        }
        if (canonical(configured).isPresent()) {
            return RELEASE;
        }
        return placeholder(configured.strip()) ? PLACEHOLDER : MALFORMED;
    }

    /**
     * The version to announce for a configured value.
     *
     * @param configured the configured text, {@code null} when absent
     * @return the canonical release version, empty when the value is not one
     */
    static Optional<String> canonical(final String configured) {

        if (configured == null) {
            return Optional.empty();
        }
        final String[] parts = configured.strip().split("\\.", -1);
        if (parts.length != PARTS) {
            return Optional.empty();
        }
        final StringBuilder version = new StringBuilder();
        for (final String part : parts) {
            if (!digits(part)) {
                return Optional.empty();
            }
            version.append(version.isEmpty() ? "" : ".").append(Integer.parseInt(part));
        }
        return Optional.of(version.toString());
    }

    /**
     * The start-up line that says what is announced and why.
     *
     * @param configured the configured text, {@code null} when absent
     * @return the line, with the value and the announced version
     */
    String line(final String configured) {

        return MessageFormat.format(line, configured == null ? "" : configured.strip(), canonical(configured).orElse(""));
    }

    /**
     * The level of the start-up line: INFO for a release or a placeholder, WARN for a malformed value, DEBUG when nothing is configured.
     *
     * @return the level
     */
    Logger.Level level() {

        return level;
    }

    /**
     * Writes the start-up line.
     *
     * @param configured the configured text, {@code null} when absent
     */
    void log(final String configured) {

        Log.log(level, line(configured));
    }

    private static boolean placeholder(final String text) {

        final boolean maven = text.length() > 1 && text.charAt(0) == MAVEN_DELIMITER && text.charAt(text.length() - 1) == MAVEN_DELIMITER;
        return maven || text.startsWith(PROPERTY_OPEN) && text.endsWith(PROPERTY_CLOSE);
    }

    private static boolean digits(final String part) {

        if (part.isEmpty() || part.length() > MAX_DIGITS) {
            return false;
        }
        for (int i = 0; i < part.length(); i++) {
            if (part.charAt(i) < '0' || part.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }
}
