package io.gluonify.plasma.fermion.client;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * Le {@link FermionTokenProvider} par défaut : {@link FermionTokenProvider#gluonify()} (jeton d'application sur la plateforme, jeton du
 * développeur sur le poste). Un bean {@code FermionTokenProvider} de l'application le remplace ({@link DefaultBean}).
 */
@ApplicationScoped
public class GluonifyTokenProducer {

    @Produces
    @DefaultBean
    @Singleton
    FermionTokenProvider gluonifyTokens() {
        return FermionTokenProvider.gluonify();
    }
}
