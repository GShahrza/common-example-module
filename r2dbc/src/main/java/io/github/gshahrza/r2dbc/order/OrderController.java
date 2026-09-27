package io.github.gshahrza.r2dbc.order;

import io.github.gshahrza.r2dbc.order.OrderService.OutOfStockException;
import java.util.Map;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
class OrderController {

    record NewOrder(String customer, long productId, int quantity) {
    }

    private final OrderRepository orders;
    private final OrderService service;

    OrderController(OrderRepository orders, OrderService service) {
        this.orders = orders;
        this.service = service;
    }

    @GetMapping("/api/orders/{id}")
    Mono<ResponseEntity<Order>> one(@PathVariable long id) {
        return orders.findById(id).map(ResponseEntity::ok).defaultIfEmpty(ResponseEntity.notFound().build());
    }

    /** Paged list for one customer, e.g. a mobile "my orders" screen. */
    @GetMapping("/api/orders")
    Flux<Order> byCustomer(@RequestParam String customer, @RequestParam(defaultValue = "0") int page,
                           @RequestParam(defaultValue = "20") int size) {
        return orders.findByCustomerOrderByIdDesc(customer, PageRequest.of(page, Math.min(size, 100)));
    }

    /** NDJSON: rows go to the client while the database is still reading. */
    @GetMapping(path = "/api/orders/stream", produces = MediaType.APPLICATION_NDJSON_VALUE)
    Flux<OrderService.OrderLine> stream(@RequestParam(defaultValue = "100000") int limit) {
        return service.stream(Math.clamp(limit, 1, 100_000));
    }

    @GetMapping("/api/orders/stream/fetched")
    Map<String, Long> fetched() {
        return Map.of("rowsFetchedFromDatabase", service.rowsFetched());
    }

    @GetMapping("/api/stats/customers")
    Flux<OrderService.CustomerStats> stats() {
        return service.statsByCustomer();
    }

    @PostMapping("/api/orders")
    @ResponseStatus(HttpStatus.CREATED)
    Mono<Order> place(@RequestBody NewOrder request) {
        return service.place(request.customer(), request.productId(), request.quantity());
    }

    @GetMapping("/api/products/{id}/stock")
    Mono<Map<String, Integer>> stock(@PathVariable long id) {
        return service.stock(id).map(stock -> Map.of("stock", stock));
    }

    @ExceptionHandler(OutOfStockException.class)
    ResponseEntity<Map<String, String>> outOfStock(OutOfStockException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", e.getMessage()));
    }
}
