package io.gluonify.plasma.quarkus;

import io.gluonify.plasma.crystallon.client.CrystallonClient;
import io.gluonify.plasma.crystallon.client.Jackson3Codec;
import io.gluonify.plasma.services.GluonifyServices;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import tools.jackson.databind.ObjectMapper;

/**
 * Gluonify's clients as CDI beans, with nothing to write in the application: {@code @Inject CrystallonClient crystallon;} is enough. The address and the
 * token come from {@link GluonifyServices} (platform, developer workstation after {@code gluonify login}, or {@code GluonifyTestServices} in
 * tests); the JSON is that of the application's {@link ObjectMapper} (Jackson 3 of Quarkus 4), so that dates and the omission of {@code null}
 * follow its configuration.
 *
 * <p>Each producer is a {@link DefaultBean}: a bean of the same type in the application replaces it (for example a {@code CrystallonClient}
 * that forwards the signed-in person's token, {@code CrystallonTokens.forwarding(...)}). The client is created on first injection (not at
 * startup): an application that does not use Crystallon does not have to declare it in {@code uses}. Nothing static: native image without
 * configuration (the client's {@code HttpClient} is created on the first call).
 *
 * <p>The default {@code FermionTokenProvider} lives in {@code gluonify-plasma-fermion-client} itself (producer {@code GluonifyTokenProducer}):
 * this module does not depend on fermion-client, which stays optional.
 */
@ApplicationScoped
public class GluonifyProducers {

    /** Name of the Crystallon service in {@code uses}. */
    public static final String CRYSTALLON = "crystallon";

    /**
     * @throws io.gluonify.plasma.services.GluonifyServicesException on first injection, if Crystallon cannot be reached (the message says
     *                                                               what to do: {@code uses}, {@code gluonify login}, {@code GluonifyTestServices})
     */
    @Produces
    @DefaultBean
    @Singleton // not ApplicationScoped: CrystallonClient is a final class that a client proxy cannot subclass
    CrystallonClient crystallon(final Instance<ObjectMapper> mapper) {
        return crystallon(GluonifyServices.defaults(), mapper.isResolvable() ? mapper.get() : null);
    }

    /** The Crystallon client resolved by {@code services}, with the JSON of {@code mapper} (or the detected one if {@code null}). */
    static CrystallonClient crystallon(final GluonifyServices services, final ObjectMapper mapper) {
        final CrystallonClient.Builder b = CrystallonClient.builder().endpoint(services.resolve(CRYSTALLON));
        if (mapper != null) {
            b.json(Jackson3Codec.of(mapper));
        }
        return b.build();
    }
}
