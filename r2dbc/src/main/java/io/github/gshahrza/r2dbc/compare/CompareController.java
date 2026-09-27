package io.github.gshahrza.r2dbc.compare;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Fires N parallel requests at one of the slow endpoints and reports how long they took. */
@RestController
class CompareController {

    record Result(String mode, int requests, long totalMillis, long slowestMillis, List<String> serverThreads) {
    }

    private final WebClient.Builder builder;
    private volatile WebClient client;

    CompareController(WebClient.Builder builder) {
        this.builder = builder;
    }

    @EventListener
    void onStart(WebServerInitializedEvent event) {
        client = builder.baseUrl("http://localhost:" + event.getWebServer().getPort()).build();
    }

    @GetMapping("/api/compare")
    Mono<Result> compare(@RequestParam String mode, @RequestParam(defaultValue = "100") int requests) {
        int n = Math.clamp(requests, 1, 400);
        long start = System.nanoTime();
        return Flux.range(0, n)
                .flatMap(i -> {
                    long sent = System.nanoTime();
                    return client.get().uri("/api/slow/{mode}", mode).retrieve()
                            .bodyToMono(new ParameterizedTypeReference<Map<String, String>>() { })
                            .map(body -> Map.entry(body.get("thread"), System.nanoTime() - sent));
                }, n)   // all n at once
                .collectList()
                .map(results -> new Result(mode, n, Duration.ofNanos(System.nanoTime() - start).toMillis(),
                        results.stream().mapToLong(Map.Entry::getValue).max().orElse(0) / 1_000_000,
                        List.copyOf(new TreeSet<>(results.stream().map(Map.Entry::getKey).toList()))));
    }
}
