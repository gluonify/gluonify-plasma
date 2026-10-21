package io.gluonify.plasma.fermion.client;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;

/**
 * The default {@link FermionTokenProvider}: {@link FermionTokenProvider#gluonify()} (application token on the platform, developer token
 * on the workstation). An application {@code FermionTokenProvider} bean replaces it ({@link DefaultBean}).
 */
@ApplicationScoped
public class GluonifyTokenProducer {

    @Produces
    @DefaultBean
    @Singleton // not ApplicationScoped: a lambda-backed functional interface read on every call, a client proxy would only add an indirection
    FermionTokenProvider gluonifyTokens() {
        return FermionTokenProvider.gluonify();
    }
}
