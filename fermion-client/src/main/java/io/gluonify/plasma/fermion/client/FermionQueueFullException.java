package io.gluonify.plasma.fermion.client;

/** The queue of {@code Start} messages emitted during a disconnection is full: the workflow is not accepted (no silent loss). */
public class FermionQueueFullException extends RuntimeException {
    public FermionQueueFullException(final int capacity) {
        super("fermion is unreachable and the queue of pending workflow starts is full (" + capacity + ")");
    }
}
