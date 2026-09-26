package io.github.gshahrza.streaming.mvc.config;

import java.time.Duration;

public final class Sleeper {

    private Sleeper() {
    }

    /** Sleeps and restores the interrupt flag, so a cancelled producer can stop. */
    public static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
