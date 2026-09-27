package io.github.gshahrza.events.order;

import io.github.gshahrza.events.Topics;
import io.github.gshahrza.events.Topics.OrderPlaced;
import io.github.gshahrza.events.Topics.PaymentResult;
import io.github.gshahrza.events.outbox.Outbox;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final Outbox outbox;

    public OrderService(OrderRepository orders, Outbox outbox) {
        this.orders = orders;
        this.outbox = outbox;
    }

    /** Saga step 1: the order and its OrderPlaced event are committed together. */
    @Transactional
    public Order place(String customer, BigDecimal amount) {
        Order order = orders.save(new Order(customer, amount));
        outbox.add(Topics.ORDERS, order.getId(), new OrderPlaced(order.getId(), customer, amount));
        return order;
    }

    /** Saga step 3: the payment outcome confirms the order or compensates it (cancels). */
    @Transactional
    public void apply(PaymentResult result) {
        orders.findById(result.orderId()).ifPresent(order -> {
            if (!order.complete(result.success(), result.reason())) {
                log.info("Order {} is already {}, ignoring {}", order.getId(), order.getStatus(), result);
            }
        });
    }
}
