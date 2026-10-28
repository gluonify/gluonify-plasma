package io.gluonify.plasma.fermion.client;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.enterprise.inject.Reserve;
import jakarta.inject.Singleton;

/**
 * The default {@link FermionTokenProvider}: {@link FermionTokenProvider#gluonify()} (application token on the platform, developer token
 * on the workstation). An application {@code FermionTokenProvider} bean replaces it ({@link Reserve} bean).
 */
@ApplicationScoped
public class GluonifyTokenProducer {

    @Produces
    @Reserve
    @Priority(0) // a reserve is enabled only with a priority (CDI 5): any application bean of the type wins over it
    // singleton scope rather than application scope: a lambda-backed functional interface read on every call, a client proxy would only add an indirection
    @Singleton
    FermionTokenProvider gluonifyTokens() {
        return FermionTokenProvider.gluonify();
    }
}
