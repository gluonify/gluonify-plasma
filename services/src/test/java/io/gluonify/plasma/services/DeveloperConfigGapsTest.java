package io.gluonify.plasma.services;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The developer's configuration file: where it lives, how a damaged one is read, what is written back. */
class DeveloperConfigGapsTest {
    @TempDir
    Path home;

    @Test
    void theFolderIsGluonifyHomeOrTheUsersDotGluonify() {
        final Map<String, String> env = new java.util.HashMap<>();
        final Map<String, String> props = Map.of("user.home", "/home/ada");
        assertEquals(Path.of("/home/ada/.gluonify"), DeveloperConfig.home(env::get, props::get));
        env.put(DeveloperConfig.ENV_HOME, "   ");
        assertEquals(Path.of("/home/ada/.gluonify"), DeveloperConfig.home(env::get, props::get), "a blank variable is no variable");
        env.put(DeveloperConfig.ENV_HOME, " /srv/gluonify ");
        assertEquals(Path.of("/srv/gluonify"), DeveloperConfig.home(env::get, props::get));
    }

    @SuppressWarnings({"deprecation", "removal"})
    @Test
    void theDeprecatedFunctionFormStillWorks() {
        final java.util.function.Function<String, String> env = k -> null;
        final java.util.function.Function<String, String> props = k -> "/home/bob";
        assertEquals(Path.of("/home/bob/.gluonify"), DeveloperConfig.home(env, props));
    }

    @Test
    void zoneNamesAreHostNamesWithoutSchemePortOrPath() {
        assertTrue(DeveloperConfig.validZone("gluonify.lan"));
        assertTrue(DeveloperConfig.validZone("a"));
        assertTrue(DeveloperConfig.validZone("a-b.c1"));
        assertFalse(DeveloperConfig.validZone(null));
        assertFalse(DeveloperConfig.validZone(""));
        assertFalse(DeveloperConfig.validZone("A.lan"));
        assertFalse(DeveloperConfig.validZone("https://a.lan"));
        assertFalse(DeveloperConfig.validZone("a.lan:8443"));
        assertFalse(DeveloperConfig.validZone("a..lan"));
        assertFalse(DeveloperConfig.validZone("-a.lan"));
        assertFalse(DeveloperConfig.validZone("a/b"));
    }

    @Test
    void anAbsentFileIsAnEmptyConfiguration() {
        assertTrue(DeveloperConfig.read(home).isEmpty());
        assertTrue(DeveloperConfig.read(home).current().isEmpty());
        assertTrue(DeveloperConfig.read(home).zones().isEmpty());
    }

    @Test
    void anInvalidFileIsReportedWithTheWayOut() throws Exception {
        Files.writeString(home.resolve(DeveloperConfig.FILE), "{not json");
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperConfig.read(home));
        assertTrue(e.getMessage().contains("is not valid JSON: fix it or run gluonify login <zone> again"), e.getMessage());
        Files.writeString(home.resolve(DeveloperConfig.FILE), "[1]");
        assertThrows(GluonifyServicesException.class, () -> DeveloperConfig.read(home));
    }

    @Test
    void anUnreadableFileIsReported() throws Exception {
        Files.write(home.resolve(DeveloperConfig.FILE), new byte[]{(byte) 0xff, (byte) 0xfe, (byte) 0xc3});
        final GluonifyServicesException e = assertThrows(GluonifyServicesException.class, () -> DeveloperConfig.read(home));
        assertTrue(e.getMessage().contains("cannot read"), e.getMessage());
    }

    @Test
    void damagedEntriesAreSkippedOrRepaired() throws Exception {
        Files.writeString(home.resolve(DeveloperConfig.FILE), """
                {"current": "ghost.lan",
                 "zones": {"ok.lan": {"discovery": "https://ok.lan/d", "environment": "DEV", "caFile": "/etc/ca.pem"},
                           "BAD NAME": {"discovery": "https://x"},
                           "notmap.lan": "text",
                           "nodisc.lan": {},
                           "baddisc.lan": {"discovery": "not a uri with spaces"}}}
                """);
        final DeveloperConfig c = DeveloperConfig.read(home);
        assertEquals(java.util.List.of("ok.lan", "nodisc.lan", "baddisc.lan"), java.util.List.copyOf(c.zones().keySet()));
        assertTrue(c.current().isEmpty(), "the current zone is not among the known ones");
        final DeveloperConfig.Zone ok = c.zone("ok.lan").orElseThrow();
        assertEquals(URI.create("https://ok.lan/d"), ok.discovery());
        assertEquals("DEV", ok.environment());
        assertEquals(Path.of("/etc/ca.pem"), ok.caFile());
        assertEquals(Discovery.defaultUrl("nodisc.lan"), c.zone("nodisc.lan").orElseThrow().discovery());
        assertEquals(Discovery.defaultUrl("baddisc.lan"), c.zone("baddisc.lan").orElseThrow().discovery(), "an unusable address is replaced by the default one");
        assertNull(c.zone("nodisc.lan").orElseThrow().caFile());
    }

    @Test
    void writingThenReadingKeepsZonesCurrentEnvironmentAndAuthority() {
        final Path ca = home.resolve("ca.pem");
        final DeveloperConfig c = DeveloperConfig.empty()
                .with(new DeveloperConfig.Zone("a.lan", URI.create("https://a.lan/d"), null, null))
                .with(new DeveloperConfig.Zone("b.lan", URI.create("https://b.lan/d"), "SBX", ca));
        c.write(home);
        final DeveloperConfig back = DeveloperConfig.read(home);
        assertEquals("b.lan", back.current().orElseThrow().name());
        assertEquals("SBX", back.zone("b.lan").orElseThrow().environment());
        assertEquals(ca.toAbsolutePath(), back.zone("b.lan").orElseThrow().caFile());
        assertNull(back.zone("a.lan").orElseThrow().environment());
    }

    @Test
    void anEmptyConfigurationIsWrittenWithoutCurrentZone() throws Exception {
        DeveloperConfig.empty().write(home);
        final String text = Files.readString(home.resolve(DeveloperConfig.FILE));
        assertFalse(text.contains("current"), text);
        assertTrue(DeveloperConfig.read(home).isEmpty());
    }

    @Test
    void anUnwritableFolderIsAnUncheckedFailure() throws Exception {
        final Path file = Files.writeString(home.resolve("not-a-folder"), "x");
        assertThrows(java.io.UncheckedIOException.class, () -> DeveloperConfig.empty().write(file));
    }

    @Test
    void forgettingTheCurrentZoneMakesTheFirstRemainingCurrent() {
        final DeveloperConfig c = DeveloperConfig.empty()
                .with(new DeveloperConfig.Zone("a.lan", URI.create("https://a.lan/d"), null, null))
                .with(new DeveloperConfig.Zone("b.lan", URI.create("https://b.lan/d"), null, null));
        assertEquals("a.lan", c.without("b.lan").current().orElseThrow().name());
        assertEquals("b.lan", c.without("a.lan").current().orElseThrow().name());
        assertTrue(c.without("a.lan").without("b.lan").current().isEmpty());
        assertEquals("b.lan", c.without("unknown.lan").current().orElseThrow().name());
        assertThrows(UnsupportedOperationException.class, () -> c.zones().clear());
    }
}
