package io.github.gshahrza.events.order;

import io.github.gshahrza.events.outbox.OutboxRelay;
import io.github.gshahrza.events.outbox.OutboxRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class OrderController {

    record PlaceOrder(@NotBlank String customer, @Positive BigDecimal amount) {
    }

    private final OrderService service;
    private final OrderRepository orders;
    private final OutboxRepository outbox;
    private final OutboxRelay relay;

    OrderController(OrderService service, OrderRepository orders, OutboxRepository outbox, OutboxRelay relay) {
        this.service = service;
        this.orders = orders;
        this.outbox = outbox;
        this.relay = relay;
    }

    /** Returns immediately with PENDING; the rest of the saga runs asynchronously. */
    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.ACCEPTED)
    Order place(@Valid @RequestBody PlaceOrder request) {
        return service.place(request.customer(), request.amount());
    }

    @GetMapping("/api/orders")
    List<Order> all() {
        return orders.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @GetMapping("/api/orders/{id}")
    ResponseEntity<Order> one(@PathVariable long id) {
        return ResponseEntity.of(orders.findById(id));
    }

    /**
     * Sends the same OrderPlaced event (same eventId) once more, as a producer retry or a
     * consumer rebalance would. The payment service must not charge twice.
     */
    @PostMapping("/api/orders/{id}/replay")
    ResponseEntity<Void> replay(@PathVariable long id) throws Exception {
        var event = outbox.findFirstByTypeAndMessageKey("OrderPlaced", String.valueOf(id));
        if (event.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        relay.send(event.get());
        return ResponseEntity.accepted().build();
    }
}
