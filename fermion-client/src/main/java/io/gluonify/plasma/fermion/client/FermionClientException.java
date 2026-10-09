package io.gluonify.plasma.fermion.client;

/** Le client ne peut pas servir la demande (inactif). */
public class FermionClientException extends RuntimeException {
    public FermionClientException(final String message) {
        super(message);
    }
}
