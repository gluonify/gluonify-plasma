package io.gluonify.plasma.fermion.client;

/** La file des {@code Start} émis pendant une coupure est pleine : le workflow n'est pas accepté (pas de perte silencieuse). */
public class FermionQueueFullException extends RuntimeException {
    public FermionQueueFullException(final int capacity) {
        super("fermion is unreachable and the queue of pending workflow starts is full (" + capacity + ")");
    }
}
