package lk.scooterrentkandy.support;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

/** System time plus an offset tests can move forward, so rentals can last hours without waiting. */
public class MutableClock extends Clock {

    private final ZoneId zone;
    private volatile Duration offset = Duration.ZERO;

    public MutableClock(ZoneId zone) {
        this.zone = zone;
    }

    public void advance(Duration d) {
        offset = offset.plus(d);
    }

    public void reset() {
        offset = Duration.ZERO;
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        MutableClock c = new MutableClock(zone);
        c.offset = offset;
        return c;
    }

    @Override
    public Instant instant() {
        return Instant.now().plus(offset);
    }
}
