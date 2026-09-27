package io.github.gshahrza.resilience;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Plays an external partner API (e.g. a bank's exchange-rate service) whose behaviour can be
 * switched, so every failure mode can be shown on demand.
 */
@RestController
class PartnerSimulator {

    enum Mode {
        OK,          // 50 ms, success
        SLOW,        // 3 s: longer than our read timeout
        ERROR,       // always 500
        FLAKY,       // calls 1 and 2 fail with 503, call 3 succeeds, and so on
        BAD_REQUEST  // 400: our request is wrong, retrying cannot help
    }

    record ModeChange(Mode mode) {
    }

    private final AtomicReference<Mode> mode = new AtomicReference<>(Mode.OK);
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong callsInMode = new AtomicLong();

    @PutMapping("/partner/mode")
    Map<String, Object> mode(@RequestBody ModeChange change) {
        mode.set(change.mode());
        callsInMode.set(0);
        return Map.of("mode", mode.get());
    }

    @GetMapping("/partner/mode")
    Map<String, Object> mode() {
        return Map.of("mode", mode.get(), "calls", calls.get());
    }

    @GetMapping("/partner/rates")
    ResponseEntity<Map<String, Object>> rates() throws InterruptedException {
        calls.incrementAndGet();
        long n = callsInMode.incrementAndGet();
        return switch (mode.get()) {
            case OK -> ok(50);
            case SLOW -> ok(3000);
            case ERROR -> ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "internal error"));
            case FLAKY -> n % 3 == 0 ? ok(50)
                    : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "try again"));
            case BAD_REQUEST -> ResponseEntity.badRequest().body(Map.of("error", "unknown currency"));
        };
    }

    long calls() {
        return calls.get();
    }

    private static ResponseEntity<Map<String, Object>> ok(long delayMillis) throws InterruptedException {
        Thread.sleep(delayMillis);
        return ResponseEntity.ok(Map.of("USD", new BigDecimal("1.70"), "EUR", new BigDecimal("1.85"),
                "at", Instant.now().toString()));
    }
}
