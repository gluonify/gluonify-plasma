package io.gluonify.plasma.fermion.client;

import static org.junit.jupiter.api.Assertions.fail;

import java.time.Duration;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/**
 * Bounded waits on a condition for the tests (no {@code Thread.sleep}).
 *
 * @author David CABILLIC
 */
final class Waits {

    private static final long STEP_NANOS = Duration.ofMillis(25).toNanos();

    private Waits() {
    }

    /**
     * Waits until the condition holds.
     *
     * @param what      what is awaited, for the failure message
     * @param condition the condition
     * @param limit     the bound
     */
    static void until(final String what, final BooleanSupplier condition, final Duration limit) {

        final long end = System.nanoTime() + limit.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                fail("timeout: " + what);
            }
            LockSupport.parkNanos(STEP_NANOS);
        }
    }

    /**
     * Checks that the condition holds all along the period (a silence, an absence of change).
     *
     * @param what      what must hold, for the failure message
     * @param condition the condition
     * @param period    how long it must hold
     */
    static void holds(final String what, final BooleanSupplier condition, final Duration period) {

        final long end = System.nanoTime() + period.toNanos();
        while (System.nanoTime() < end) {
            if (!condition.getAsBoolean()) {
                fail("no longer holds: " + what);
            }
            LockSupport.parkNanos(STEP_NANOS);
        }
    }

    /**
     * Waits until the value has stopped changing for the given time.
     *
     * @param value  the value watched
     * @param stable how long it must stay the same
     * @param limit  the bound
     */
    static void stable(final IntSupplier value, final Duration stable, final Duration limit) {

        final long end = System.nanoTime() + limit.toNanos();
        int last = value.getAsInt();
        long since = System.nanoTime();
        while (System.nanoTime() - since < stable.toNanos()) {
            if (System.nanoTime() > end) {
                fail("timeout: the value does not settle");
            }
            LockSupport.parkNanos(STEP_NANOS);
            final int now = value.getAsInt();
            if (now != last) {
                last = now;
                since = System.nanoTime();
            }
        }
    }
}
