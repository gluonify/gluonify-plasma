package io.gluonify.plasma.fermion.client;

/** The client cannot serve the request (inactive). */
public class FermionClientException extends RuntimeException {
    public FermionClientException(final String message) {
        super(message);
    }
}
