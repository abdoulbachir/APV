package org.apvgakasinay.probe;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** The probe's notion of now. Tests move it forward to reach deadlines without waiting. */
@Component
public class ProbeClock {

    private final AtomicReference<Duration> offset = new AtomicReference<>(Duration.ZERO);

    public Instant now() {
        return Instant.now().plus(offset.get());
    }

    public void advance(Duration duration) {
        offset.updateAndGet(current -> current.plus(duration));
    }

    public void reset() {
        offset.set(Duration.ZERO);
    }
}
