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
 * Les clients de Gluonify en beans CDI, sans rien écrire dans l'application : {@code @Inject CrystallonClient crystallon;} suffit. L'adresse et le
 * jeton viennent de {@link GluonifyServices} (plateforme, poste du développeur après {@code gluonify login}, ou {@code GluonifyTestServices} en
 * test) ; le JSON est celui du {@link ObjectMapper} de l'application (Jackson 3 de Quarkus 4), pour que les dates et l'omission des {@code null}
 * suivent sa configuration.
 *
 * <p>Chaque producteur est un {@link DefaultBean} : un bean du même type dans l'application le remplace (par exemple un {@code CrystallonClient}
 * qui transmet le jeton de la personne connectée, {@code CrystallonTokens.forwarding(...)}). Le client est créé à la première injection (pas au
 * démarrage) : une application qui n'utilise pas Crystallon n'a pas à le déclarer dans {@code uses}. Rien de statique : image native sans
 * configuration (le {@code HttpClient} du client est créé au premier appel).
 *
 * <p>Le {@code FermionTokenProvider} par défaut est dans {@code gluonify-plasma-fermion-client} lui-même (producteur {@code GluonifyTokenProducer}) :
 * ce module ne dépend pas de fermion-client, qui reste facultatif.
 */
@ApplicationScoped
public class GluonifyProducers {

    /** Nom du service Crystallon dans {@code uses}. */
    public static final String CRYSTALLON = "crystallon";

    /**
     * @throws io.gluonify.plasma.services.GluonifyServicesException à la première injection, si Crystallon ne peut pas être joint (le message dit
     *                                                               quoi faire : {@code uses}, {@code gluonify login}, {@code GluonifyTestServices})
     */
    @Produces
    @DefaultBean
    @Singleton
    CrystallonClient crystallon(final Instance<ObjectMapper> mapper) {
        return crystallon(GluonifyServices.defaults(), mapper.isResolvable() ? mapper.get() : null);
    }

    /** Le client de Crystallon résolu par {@code services}, avec le JSON de {@code mapper} (ou celui détecté si {@code null}). */
    static CrystallonClient crystallon(final GluonifyServices services, final ObjectMapper mapper) {
        final CrystallonClient.Builder b = CrystallonClient.builder().endpoint(services.resolve(CRYSTALLON));
        if (mapper != null) {
            b.json(Jackson3Codec.of(mapper));
        }
        return b.build();
    }
}
