package io.github.gshahrza.r2dbc.compare;

import java.util.Map;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The same 200 ms query three ways. Each answer names the thread that ran it.
 */
@RestController
class SlowQueryController {

    private static final String SQL = "SELECT pg_sleep(0.2)";

    private final DatabaseClient r2dbc;
    private final JdbcClient jdbc;

    SlowQueryController(DatabaseClient r2dbc, JdbcClient jdbc) {
        this.r2dbc = r2dbc;
        this.jdbc = jdbc;
    }

    /** Non-blocking: the event-loop thread sends the query and is free until the answer arrives. */
    @GetMapping("/api/slow/r2dbc")
    Mono<Map<String, String>> r2dbc() {
        return r2dbc.sql(SQL).then().then(Mono.fromSupplier(SlowQueryController::thread));
    }

    /**
     * The mistake: JDBC inside a WebFlux handler. The event-loop thread waits 200 ms, and every
     * other request assigned to that thread waits with it.
     */
    @GetMapping("/api/slow/jdbc-on-event-loop")
    Mono<Map<String, String>> jdbcOnEventLoop() {
        return Mono.fromCallable(() -> {
            jdbc.sql(SQL).query().singleRow();
            return thread();
        });
    }

    /**
     * The workaround when a library is blocking: move the call to boundedElastic, a pool meant
     * for blocking work. The event loop stays free, but every waiting call occupies a thread.
     */
    @GetMapping("/api/slow/jdbc-offloaded")
    Mono<Map<String, String>> jdbcOffloaded() {
        return jdbcOnEventLoop().subscribeOn(Schedulers.boundedElastic());
    }

    private static Map<String, String> thread() {
        return Map.of("thread", Thread.currentThread().getName());
    }
}
