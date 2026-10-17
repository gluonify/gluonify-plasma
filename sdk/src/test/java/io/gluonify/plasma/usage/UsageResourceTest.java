package io.gluonify.plasma.usage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.gluonify.plasma.usage.UsageReport.AppShare;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** The common contract test applied to the SDK resource, then its own behaviours (without a CDI container: test constructor). */
class UsageResourceTest extends UsageContractTests {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String SECRET = "s3cret-value";

    /** A contributor that keeps a secret in memory: the response knows nothing about it. */
    static final UsageContributor CONTRIBUTOR = new UsageContributor() {
        @SuppressWarnings("unused")
        private final String password = SECRET;

        @Override
        public List<AppShare> byApp() {
            return List.of(new AppShare("shop", "u1", 0.001, 1024, "estimated", "db app1", 300L));
        }

        @Override
        public Long storageBytes() {
            return 1000L;
        }
    };

    static SecurityContext caller(final Set<String> roles) {
        return new SecurityContext() {
            @Override
            public Principal getUserPrincipal() {
                return roles == null ? null : () -> "svc";
            }

            @Override
            public boolean isUserInRole(final String role) {
                return roles != null && roles.contains(role);
            }

            @Override
            public boolean isSecure() {
                return true;
            }

            @Override
            public String getAuthenticationScheme() {
                return "Bearer";
            }
        };
    }

    /** A fake {@code /proc}: 12 s of CPU (1,200 ticks), 8 MiB resident. */
    static Path fakeProc() {
        try {
            final Path proc = Files.createTempDirectory("usage-proc");
            proc.toFile().deleteOnExit();
            Files.writeString(proc.resolve("stat"), "1 (app) S 1 1 1 0 -1 0 0 0 0 0 1000 200 0 0 20 0 4 0 1 1 1\n");
            Files.writeString(proc.resolve("status"), "VmRSS:\t8192 kB\n");
            return proc;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static UsageResource resource(final UsageContributor... contributors) {
        return resource(fakeProc(), contributors);
    }

    static UsageResource resource(final Path proc, final UsageContributor... contributors) {
        return new UsageResource(new ProcessUsage(proc, Path.of("/nonexistent"), 100, () -> 0, 0, () -> 1), () -> 1760700000000L,
                () -> List.of(contributors), "gdown", "0.7.0", "gluonify-app-gdown-1", "n1");
    }

    @Override
    protected byte[] authorizedBody() {
        final Response r = resource(CONTRIBUTOR).usage(caller(Set.of(UsageResource.ROLE)));
        assertEquals(200, r.getStatus());
        return JSON.writeValueAsBytes(r.getEntity());
    }

    @Override
    protected int statusWithoutToken() {
        return resource(CONTRIBUTOR).usage(caller(null)).getStatus();
    }

    @Override
    protected int statusWithoutRole() {
        return resource(CONTRIBUTOR).usage(caller(Set.of("gluonify:other:usage:read"))).getStatus();
    }

    @Override
    protected List<String> secrets() {
        return List.of(SECRET);
    }

    /** The test contributor stores data: the inherited {@code storageIsConsistent} requires the total. */
    @Override
    protected boolean storesData() {
        return true;
    }

    @Test
    void productRoleSufficesAndResponseNotCached() {
        final Response r = resource().usage(caller(Set.of("gluonify:gdown:usage:read")));
        assertEquals(200, r.getStatus());
        assertEquals("no-store", r.getHeaderString("Cache-Control"));
        assertEquals(401, resource().usage(null).getStatus());
    }

    @Test
    void withoutContributorAttributionNoneAndProcessOnly() {
        final UsageReport r = resource().report();
        assertEquals(UsageReport.NONE, r.attribution());
        assertTrue(r.byApp().isEmpty());
        assertEquals("gdown", r.component());
        assertEquals("gluonify-app-gdown-1", r.instance());
        assertEquals(12.0, r.process().cpuSecondsTotal());
        assertEquals(8192L * 1024, r.process().memoryBytes());
        // /proc absent : champs absents, pas d'exception
        assertNull(resource(Path.of("/nonexistent")).report().process().cpuSecondsTotal());
    }

    @Test
    void failingContributorDoesNotFailTheContract() {
        final UsageContributor broken = () -> {
            throw new IllegalStateException("boom " + SECRET);
        };
        final UsageReport r = resource(broken).report();
        assertEquals(UsageReport.NONE, r.attribution());
        assertTrue(r.byApp().isEmpty());
    }

    @Test
    void weakestAttributionWins() {
        final UsageContributor measured = new UsageContributor() {
            @Override
            public List<AppShare> byApp() {
                return List.of(new AppShare("a", "u", 0, 1, "measured"));
            }

            @Override
            public String attribution() {
                return UsageReport.MEASURED;
            }
        };
        assertEquals(UsageReport.MEASURED, resource(measured).report().attribution());
        assertEquals(UsageReport.ESTIMATED, resource(measured, CONTRIBUTOR).report().attribution());
    }

    /** The inherited {@code noSecret} check is not tautological: a value of {@link #secrets()} that leaked into the body would be caught. */
    @Test
    void aLeakingSecretWouldBeDetected() {
        final UsageContributor leaking = () -> List.of(new AppShare("shop", "u1", 0.001, 1024, "estimated", "db " + SECRET));
        final Response r = resource(leaking).usage(caller(Set.of(UsageResource.ROLE)));
        final String body = new String(JSON.writeValueAsBytes(r.getEntity()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(secrets().stream().anyMatch(body::contains));
        assertFalse(new String(authorizedBody(), java.nio.charset.StandardCharsets.UTF_8).contains(SECRET));
    }
}
