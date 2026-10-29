package io.gluonify.plasma.logging;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.Test;

/**
 * {@link JwsRedactionFilter} without Quarkus: tokens in the message, in printf and MessageFormat parameters and in plain records are
 * replaced, everything else is left untouched, and no record is ever dropped.
 *
 * @author David CABILLIC
 */
class JwsRedactionFilterTest {

    private static final String CATEGORY = "io.quarkus.oidc.runtime.OidcProvider";

    private final JwsRedactionFilter filter = new JwsRedactionFilter();

    @Test
    void aTokenInAPrintfParameterIsRedacted() {

        final ExtLogRecord logRecord = ext("Token verification has failed: Unable to process JOSE object: %s", ExtLogRecord.FormatStyle.PRINTF, sampleJws());
        assertTrue(filter.isLoggable(logRecord));
        assertEquals("Token verification has failed: Unable to process JOSE object: " + JwsRedactionFilter.REDACTED, logRecord.getFormattedMessage());
        assertNull(logRecord.getParameters(), "no parameter is left to put the token back");
    }

    @Test
    void aTokenInAMessageFormatParameterIsRedacted() {

        final ExtLogRecord logRecord = ext("refused {0} for {1}", ExtLogRecord.FormatStyle.MESSAGE_FORMAT, sampleJws(), "alice");
        filter.isLoggable(logRecord);
        assertEquals("refused " + JwsRedactionFilter.REDACTED + " for alice", logRecord.getFormattedMessage());
    }

    @Test
    void aTokenWrittenInTheMessageItselfIsRedacted() {

        final ExtLogRecord logRecord = ext("Authorization: Bearer " + sampleJws() + " (twice: " + sampleJws() + ")", ExtLogRecord.FormatStyle.NO_FORMAT);
        filter.isLoggable(logRecord);
        assertEquals("Authorization: Bearer " + JwsRedactionFilter.REDACTED + " (twice: " + JwsRedactionFilter.REDACTED + ")", logRecord.getFormattedMessage());
    }

    @Test
    void aPlainRecordIsFormattedThenRedacted() {

        final LogRecord logRecord = new LogRecord(Level.WARNING, "token {0}");
        logRecord.setParameters(new Object[] {sampleJws()});
        assertTrue(filter.isLoggable(logRecord));
        assertEquals("token " + JwsRedactionFilter.REDACTED, logRecord.getMessage());
        assertNull(logRecord.getParameters());
    }

    @Test
    void aPlainRecordWithoutTokenKeepsItsParameters() {

        final LogRecord logRecord = new LogRecord(Level.INFO, "eyJ alone {0}");
        final Object[] parameters = {"value"};
        logRecord.setParameters(parameters);
        assertTrue(filter.isLoggable(logRecord));
        assertEquals("eyJ alone {0}", logRecord.getMessage());
        assertEquals(parameters, logRecord.getParameters());
    }

    @Test
    void aMessageWithoutTokenIsLeftAsItIs() {

        final ExtLogRecord logRecord = ext("node %s joined", ExtLogRecord.FormatStyle.PRINTF, "quark");
        assertTrue(filter.isLoggable(logRecord));
        assertEquals("node %s joined", logRecord.getMessage(), "the message and its parameters are not rewritten");
        assertEquals(1, logRecord.getParameters().length);
    }

    @Test
    void aRecordWithoutMessageIsLogged() {

        assertTrue(filter.isLoggable(new LogRecord(Level.INFO, null)));
    }

    @Test
    void textsThatOnlyLookCloseAreNotTouched() {

        assertEquals("eyJ.", JwsRedactionFilter.redact("eyJ."));
        assertEquals("eyJab.cd", JwsRedactionFilter.redact("eyJab.cd"), "two parts are not a JWS");
        assertEquals("version eyJhb", JwsRedactionFilter.redact("version eyJhb"));
        assertNull(JwsRedactionFilter.redact(null));
        assertFalse(JwsRedactionFilter.redact("a " + sampleJws() + " b").contains("eyJ"));
    }

    @Test
    void anUnsignedTokenIsRedactedToo() {

        final String unsigned = sampleJws().substring(0, sampleJws().lastIndexOf('.') + 1);
        assertEquals("x " + JwsRedactionFilter.REDACTED + " y", JwsRedactionFilter.redact("x " + unsigned + " y"));
    }

    private static ExtLogRecord ext(final String message, final ExtLogRecord.FormatStyle style, final Object... parameters) {

        final ExtLogRecord logRecord = new ExtLogRecord(org.jboss.logmanager.Level.WARN, message, style, JwsRedactionFilterTest.class.getName());
        logRecord.setLoggerName(CATEGORY);
        logRecord.setParameters(parameters.length == 0 ? null : parameters);
        return logRecord;
    }

    /** A JWS-shaped sample built at run time (header.payload.signature), so that no token-like literal sits in the sources. */
    static String sampleJws() {

        final Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return String.join(".", encoder.encodeToString("{\"alg\":\"RS256\"}".getBytes(UTF_8)), encoder.encodeToString("{\"sub\":\"sample\"}".getBytes(UTF_8)),
                encoder.encodeToString("signature".getBytes(UTF_8)));
    }
}
