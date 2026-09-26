package io.github.gshahrza.streaming.webflux.dashboard;

import java.time.Duration;
import java.util.List;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/** Three slow "remote" services. In a real app these would be WebClient calls to other microservices. */
@Component
public class RemoteServices {

    public Mono<String> user(String id) {
        return Mono.delay(Duration.ofMillis(300)).map(t -> "User " + id);
    }

    public Mono<List<String>> orders(String id) {
        return Mono.delay(Duration.ofMillis(500)).map(t -> List.of("ORD-1", "ORD-2", "ORD-3"));
    }

    public Mono<List<String>> recommendations(String id) {
        return Mono.delay(Duration.ofMillis(400)).map(t -> List.of("Laptop", "Headphones"));
    }
}
