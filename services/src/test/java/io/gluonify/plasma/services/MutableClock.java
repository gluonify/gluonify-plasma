package io.gluonify.plasma.services;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** Test clock, advanced by hand. */
public final class MutableClock extends Clock {
    private volatile Instant now;

    public MutableClock(final Instant start) {
        this.now = start;
    }

    public void advance(final Duration d) {
        now = now.plus(d);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(final ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now;
    }
}
