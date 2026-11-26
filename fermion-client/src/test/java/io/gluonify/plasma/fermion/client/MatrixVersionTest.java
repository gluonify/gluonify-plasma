package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Which configured gluonify-matrix versions the client announces, in which form, and the start-up line that says why. */
class MatrixVersionTest {

    @Test
    void aReleaseVersionIsAnnouncedStripped() {

        assertEquals(MatrixVersion.RELEASE, MatrixVersion.of(" 0.9.4 "));
        assertEquals(Optional.of("0.9.4"), MatrixVersion.canonical(" 0.9.4 "));
        assertEquals(Optional.of("9999.0.10"), MatrixVersion.canonical("9999.0.10"), "up to four digits per number");
    }

    @Test
    void aLeadingZeroIsAnnouncedInTheCanonicalFormFermionRecords() {

        assertEquals(MatrixVersion.RELEASE, MatrixVersion.of("01.2.3"));
        assertEquals(Optional.of("1.2.3"), MatrixVersion.canonical("01.2.3"));
        assertEquals(Optional.of("0.9.5"), MatrixVersion.canonical("0.09.0005"));
        assertEquals("fermion-client=matrix version=1.2.3 announced (configured 01.2.3)", MatrixVersion.RELEASE.line("01.2.3"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void nothingConfiguredAnnouncesNothing(final String configured) {

        assertEquals(MatrixVersion.ABSENT, MatrixVersion.of(configured));
        assertEquals(Optional.empty(), MatrixVersion.canonical(configured));
    }

    @Test
    void anAbsentValueAnnouncesNothingAndLogsOnlyAtDebug() {

        assertEquals(MatrixVersion.ABSENT, MatrixVersion.of(null));
        assertEquals(Optional.empty(), MatrixVersion.canonical(null));
        assertEquals(org.jboss.logging.Logger.Level.DEBUG, MatrixVersion.ABSENT.level());
        assertEquals("fermion-client=matrix none configured, nothing announced", MatrixVersion.ABSENT.line(null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"@gluonify.matrix.version@", "${gluonify.matrix.version}", " @gluonify.matrix.version@ ", "${gluonify.matrix.version:}"})
    void anUnfilteredPlaceholderIsExpectedInDevModeAndAnnouncesNothing(final String configured) {

        assertEquals(MatrixVersion.PLACEHOLDER, MatrixVersion.of(configured));
        assertEquals(Optional.empty(), MatrixVersion.canonical(configured));
        assertEquals(org.jboss.logging.Logger.Level.INFO, MatrixVersion.PLACEHOLDER.level());
        assertEquals("fermion-client=matrix value=" + configured.strip() + " is an unfiltered placeholder (dev mode reads the source resource), nothing announced",
                MatrixVersion.PLACEHOLDER.line(configured));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.2", "1.2.3-rc1", "1.2.3.4", "[1.0.0,2.0.0)", "1..2", "1.2.x", "12345.0.0", "+1.2.3", "v1.2.3", "@", "@1.2.3", "${x"})
    void anyOtherValueIsMalformedAndAnnouncesNothing(final String configured) {

        assertEquals(MatrixVersion.MALFORMED, MatrixVersion.of(configured));
        assertEquals(Optional.empty(), MatrixVersion.canonical(configured));
        assertEquals(org.jboss.logging.Logger.Level.WARN, MatrixVersion.MALFORMED.level());
        assertEquals("fermion-client=matrix value=" + configured + " is not a release version x.y.z (one to four digits each), nothing announced",
                MatrixVersion.MALFORMED.line(configured));
    }

    @Test
    void theLineIsWrittenAtItsLevel() {

        final List<LogRecord> records = new CopyOnWriteArrayList<>();
        final Handler handler = new Handler() {

            @Override
            public void publish(final LogRecord logRecord) {

                records.add(logRecord);
            }

            @Override
            public void flush() {
                // nothing buffered
            }

            @Override
            public void close() {
                // nothing held
            }
        };
        final Logger logger = Logger.getLogger(MatrixVersion.class.getName());
        logger.addHandler(handler);
        try {
            MatrixVersion.MALFORMED.log("1.2.3-rc1");
            MatrixVersion.PLACEHOLDER.log("@gluonify.matrix.version@");
        }
        finally {
            logger.removeHandler(handler);
        }
        assertEquals(2, records.size());
        assertEquals(Level.WARNING.intValue(), records.get(0).getLevel().intValue());
        assertTrue(records.get(0).getMessage().contains("value=1.2.3-rc1 is not a release version"), records.get(0).getMessage());
        assertEquals(Level.INFO.intValue(), records.get(1).getLevel().intValue());
        assertTrue(records.get(1).getMessage().contains("unfiltered placeholder"), records.get(1).getMessage());
    }
}
