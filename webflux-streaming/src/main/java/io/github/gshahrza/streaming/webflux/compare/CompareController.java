package io.github.gshahrza.streaming.webflux.compare;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;

/**
 * "Normal request" vs WebFlux: the same 300 ms of waiting written three ways.
 *
 * <p>WebFlux runs on Netty with only a handful of event-loop threads (usually one per CPU core).
 * They must never block: while one sleeps, every other request assigned to it waits too.
 */
@RestController
public class CompareController {

    private final WebClient webClient;

    public CompareController(WebClient.Builder builder) {
        // Allow many parallel connections for the load test (the default pool is much smaller)
        ConnectionProvider pool = ConnectionProvider.builder("load-test")
                .maxConnections(1000)
                .pendingAcquireMaxCount(-1)
                .build();
        this.webClient = builder.clientConnector(new ReactorClientHttpConnector(HttpClient.create(pool))).build();
    }

    /** WRONG in WebFlux: Thread.sleep (or JDBC, or RestTemplate) blocks an event-loop thread. */
    @GetMapping("/api/compare/blocking")
    public CallResult blocking(@RequestParam(defaultValue = "300") long delayMs) throws InterruptedException {
        String handler = Thread.currentThread().getName();
        Thread.sleep(delayMs);
        return new CallResult("blocking", handler, Thread.currentThread().getName());
    }

    /** RIGHT: the wait is a timer. The event-loop thread is free until the timer fires. */
    @GetMapping("/api/compare/reactive")
    public Mono<CallResult> reactive(@RequestParam(defaultValue = "300") long delayMs) {
        String handler = Thread.currentThread().getName();
        return Mono.delay(Duration.ofMillis(delayMs))
                .map(tick -> new CallResult("reactive", handler, Thread.currentThread().getName()));
    }

    /**
     * When blocking code cannot be avoided (legacy JDBC client, file IO): move it to boundedElastic,
     * a thread pool meant for blocking work, and keep the event loop free.
     */
    @GetMapping("/api/compare/offloaded")
    public Mono<CallResult> offloaded(@RequestParam(defaultValue = "300") long delayMs) {
        String handler = Thread.currentThread().getName();
        return Mono.fromCallable(() -> {
                    Thread.sleep(delayMs);
                    return new CallResult("offloaded", handler, Thread.currentThread().getName());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Fires {@code requests} parallel calls at one of the endpoints above and measures the total time.
     * Done on the server with WebClient because a browser only opens ~6 connections per host.
     */
    @GetMapping("/api/compare/load")
    public Mono<LoadResult> load(@RequestParam String mode,
                                 @RequestParam(defaultValue = "100") int requests,
                                 @RequestParam(defaultValue = "300") long delayMs,
                                 ServerHttpRequest request) {
        if (!Set.of("blocking", "reactive", "offloaded").contains(mode) || requests < 1 || requests > 500) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "mode must be blocking|reactive|offloaded and requests 1..500"));
        }
        String url = "http://localhost:" + request.getLocalAddress().getPort()
                + "/api/compare/" + mode + "?delayMs=" + delayMs;
        long start = System.nanoTime();
        return Flux.range(0, requests)
                .flatMap(i -> webClient.get().uri(url).retrieve().bodyToMono(CallResult.class), requests)
                .map(CallResult::handlerThread)
                .collect(TreeSet<String>::new, Set::add)
                .map(threads -> new LoadResult(mode, requests, delayMs,
                        (System.nanoTime() - start) / 1_000_000, threads.size(), List.copyOf(threads)));
    }
}
