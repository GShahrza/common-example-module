package io.github.gshahrza.streaming.webflux.order;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;

/**
 * One method, two formats. The Accept header decides:
 * <ul>
 *   <li>{@code application/x-ndjson}: each Order is written as its own line as soon as it is emitted</li>
 *   <li>{@code application/json}: the same Flux is written as one JSON array; the client can only
 *       parse it after the closing bracket</li>
 * </ul>
 */
@RestController
public class OrderController {

    private static final Logger log = LoggerFactory.getLogger(OrderController.class);

    private final OrderRepository repository;
    private final AtomicLong rowsSent = new AtomicLong();
    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong cancelled = new AtomicLong();

    public OrderController(OrderRepository repository) {
        this.repository = repository;
    }

    @GetMapping(path = "/api/orders", produces = {MediaType.APPLICATION_NDJSON_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Flux<Order> orders(@RequestParam(defaultValue = "1000") int count) {
        if (count < 1 || count > 1_000_000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "count must be between 1 and 1000000");
        }
        return repository.findAll(count)
                .doOnNext(o -> rowsSent.incrementAndGet())
                .doOnComplete(completed::incrementAndGet)
                // Called when the client disconnects (closed tab, AbortController): the Flux stops here
                .doOnCancel(() -> {
                    cancelled.incrementAndGet();
                    log.info("Client cancelled the order stream; remaining pages will not be queried");
                });
    }

    /** Lets the demo page show that a stopped stream really stopped on the server too. */
    @GetMapping("/api/orders/stats")
    public Map<String, Long> stats() {
        return Map.of(
                "rowsSent", rowsSent.get(),
                "pagesQueried", repository.pagesQueried(),
                "completedStreams", completed.get(),
                "cancelledStreams", cancelled.get());
    }
}
