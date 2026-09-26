package io.github.gshahrza.streaming.webflux.dashboard;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * One page needs data from three services (300 + 500 + 400 ms).
 * Called one after another it takes their sum; with {@code Mono.zip} it takes only the slowest one,
 * without creating a single extra thread.
 */
@RestController
public class DashboardController {

    private final RemoteServices services;

    public DashboardController(RemoteServices services) {
        this.services = services;
    }

    @GetMapping("/api/dashboard/{userId}/sequential")
    public Mono<DashboardResponse> sequential(@PathVariable String userId) {
        return services.user(userId)
                .flatMap(user -> services.orders(userId)
                        .flatMap(orders -> services.recommendations(userId)
                                .map(recs -> new Dashboard(user, orders, recs))))
                .elapsed()
                .map(t -> new DashboardResponse("sequential", t.getT1(), t.getT2()));
    }

    @GetMapping("/api/dashboard/{userId}/parallel")
    public Mono<DashboardResponse> parallel(@PathVariable String userId) {
        return Mono.zip(services.user(userId), services.orders(userId), services.recommendations(userId))
                .map(t -> new Dashboard(t.getT1(), t.getT2(), t.getT3()))
                .elapsed()
                .map(t -> new DashboardResponse("parallel", t.getT1(), t.getT2()));
    }
}
