package com.rodrigues.keyra;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * The real clock plus an offset tests can move forward. Shared by every integration test, so only
 * advance it and only assert on times relative to it.
 */
public class MutableClock extends Clock {

    private final Clock base = Clock.tick(Clock.systemUTC(), Duration.ofNanos(1_000));
    private volatile Duration offset = Duration.ZERO;

    public void advance(Duration amount) {
        offset = offset.plus(amount);
    }

    @Override
    public Instant instant() {
        return base.instant().plus(offset);
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        throw new UnsupportedOperationException("MutableClock is always UTC");
    }
}
