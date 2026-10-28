package io.gluonify.plasma.quarkus;

import static org.junit.jupiter.api.Assertions.*;

import io.gluonify.plasma.crystallon.client.CrystallonClient;
import io.gluonify.plasma.services.GluonifyServices;
import jakarta.enterprise.inject.Instance;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

/** The producer's two choices of JSON mapper, without a container: the application's mapper when there is one, the detected one otherwise. */
class GluonifyProducersPlainTest {
    @TempDir
    Path home;

    @SuppressWarnings("unchecked")
    static Instance<ObjectMapper> mappers(final ObjectMapper mapper) {
        return (Instance<ObjectMapper>) Proxy.newProxyInstance(GluonifyProducersPlainTest.class.getClassLoader(), new Class<?>[]{Instance.class}, (p, m, a) -> switch (m.getName()) {
            case "isResolvable" -> mapper != null;
            case "get" -> mapper;
            default -> throw new UnsupportedOperationException(m.getName());
        });
    }

    GluonifyServices overridden() {
        final Map<String, String> props = Map.of("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://127.0.0.1:1");
        return GluonifyServices.builder().environment(k -> null).properties(props::get).home(home).build();
    }

    @Test
    void withoutAMapperTheDetectedJsonIsUsed() {
        assertNotNull(GluonifyProducers.crystallon(overridden(), null));
    }

    @Test
    void withTheApplicationsMapperItsJsonIsUsed() {
        assertNotNull(GluonifyProducers.crystallon(overridden(), new ObjectMapper()));
    }

    @Test
    void theProducerAsksTheContainerForTheMapperAndFallsBackWhenThereIsNone() {
        System.setProperty("GLUONIFY_SERVICE_CRYSTALLON_URL", "http://127.0.0.1:1");
        try {
            final GluonifyProducers producers = new GluonifyProducers();
            final CrystallonClient without = producers.crystallon(mappers(null));
            final CrystallonClient with = producers.crystallon(mappers(new ObjectMapper()));
            assertNotNull(without);
            assertNotNull(with);
            assertNotSame(without, with);
        }
        finally {
            System.clearProperty("GLUONIFY_SERVICE_CRYSTALLON_URL");
        }
        assertEquals("crystallon", GluonifyProducers.CRYSTALLON_SERVICE);
    }
}
