package io.gluonify.plasma.logging;

import io.quarkus.logging.LoggingFilter;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;
import java.util.regex.Pattern;
import org.jboss.logmanager.ExtLogRecord;

/**
 * Log filter that rewrites anything shaped like a JWT/JWS ({@code eyJ<header>.<payload>.<signature>}, base64url) in the formatted message
 * of a record, so that a bearer token never reaches a log line: Quarkus OIDC's WARN "Token verification has failed" prints the whole
 * refused token. It never drops a record. Enabled on the console, file and syslog handlers by this module's
 * {@code META-INF/microprofile-config.properties}.
 *
 * @author David CABILLIC
 */
@LoggingFilter(name = JwsRedactionFilter.NAME)
public final class JwsRedactionFilter implements Filter {

    /** Name of the filter in the handlers' {@code filter} configuration. */
    public static final String NAME = "gluonify-jws-redaction";

    /** What replaces a token in the message. */
    public static final String REDACTED = "<redacted JWS>";

    /**
     * A JOSE compact serialisation: a JSON header in base64url always starts with {@code eyJ} ({@code {"}), then at least two more
     * dot-separated base64url parts. Every part is bounded and the dot is not in the classes, so the match is linear.
     */
    private static final Pattern JWS = Pattern.compile("eyJ[\\w-]{2,4096}\\.[\\w-]{2,65536}\\.[\\w-]{0,4096}");

    /** Formats the parameters of a plain {@code java.util.logging} record (MessageFormat style). */
    private static final SimpleFormatter PLAIN = new SimpleFormatter();

    /**
     * Rewrites the tokens of the record's formatted message; the record is always logged.
     *
     * @param logRecord The record about to be written
     * @return {@code true}: the filter redacts, it never drops
     */
    @Override
    public boolean isLoggable(final LogRecord logRecord) {

        final String formatted = formatted(logRecord);
        if (formatted != null && formatted.contains("eyJ")) {
            final String redacted = redact(formatted);
            if (!redacted.equals(formatted)) {
                replaceMessage(logRecord, redacted);
            }
        }
        return true;
    }

    /**
     * Replaces every JWT/JWS of a text.
     *
     * @param text Any text, {@code null} included
     * @return The text with each token replaced by {@link #REDACTED}, or {@code null} for {@code null}
     */
    public static String redact(final String text) {

        return text == null ? null : JWS.matcher(text).replaceAll(REDACTED);
    }

    // the message as the formatter would write it: parameters applied (printf or MessageFormat style)
    private static String formatted(final LogRecord logRecord) {

        return logRecord instanceof ExtLogRecord ext ? ext.getFormattedMessage() : PLAIN.formatMessage(logRecord);
    }

    // the redacted text becomes the message, with no parameter left to apply
    private static void replaceMessage(final LogRecord logRecord, final String message) {

        if (logRecord instanceof ExtLogRecord ext) {
            ext.setMessage(message, ExtLogRecord.FormatStyle.NO_FORMAT);
        } else {
            logRecord.setMessage(message);
        }
        logRecord.setParameters(null);
    }
}
