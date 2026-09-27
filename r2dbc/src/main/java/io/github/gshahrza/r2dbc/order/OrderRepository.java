package io.github.gshahrza.r2dbc.order;

import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Like a JPA repository, but every method returns Mono (0..1) or Flux (0..n). */
public interface OrderRepository extends ReactiveCrudRepository<Order, Long> {

    Flux<Order> findByCustomerOrderByIdDesc(String customer, Pageable page);

    Mono<Long> countByCustomer(String customer);
}
