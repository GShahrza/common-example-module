package io.github.gshahrza.events.payment;

import io.github.gshahrza.events.Topics;
import io.github.gshahrza.events.Topics.OrderPlaced;
import io.github.gshahrza.events.Topics.PaymentResult;
import io.github.gshahrza.events.outbox.Outbox;
import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

    /** Orders above this amount are declined: a business "no", not an error. */
    static final BigDecimal LIMIT = new BigDecimal("1000");
    /** This amount simulates a payment gateway that is down: every attempt fails. */
    static final BigDecimal GATEWAY_DOWN = new BigDecimal("13");

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final ProcessedMessageRepository processed;
    private final DeadLetterRepository deadLetters;
    private final Outbox outbox;
    private final AtomicLong duplicatesSkipped = new AtomicLong();

    PaymentService(PaymentRepository payments, ProcessedMessageRepository processed,
                   DeadLetterRepository deadLetters, Outbox outbox) {
        this.payments = payments;
        this.processed = processed;
        this.deadLetters = deadLetters;
        this.outbox = outbox;
    }

    /**
     * Saga step 2. The dedup row, the payment and the PaymentResult event are one transaction:
     * if anything fails, none of them is saved and the message is retried.
     */
    @Transactional
    public void charge(String eventId, OrderPlaced order) {
        if (processed.existsById(eventId)) {
            duplicatesSkipped.incrementAndGet();
            log.info("Event {} already processed, skipping duplicate for order {}", eventId, order.orderId());
            return;
        }
        processed.save(new ProcessedMessage(eventId));

        if (order.amount().compareTo(GATEWAY_DOWN) == 0) {
            throw new PaymentGatewayException("Payment gateway timeout for order " + order.orderId());
        }
        boolean success = order.amount().compareTo(LIMIT) <= 0;
        String reason = success ? null : "Amount exceeds the limit of " + LIMIT;
        payments.save(new Payment(order.orderId(), order.amount(), success, reason));
        outbox.add(Topics.PAYMENTS, order.orderId(), new PaymentResult(order.orderId(), success, reason));
    }

    public long duplicatesSkipped() {
        return duplicatesSkipped.get();
    }

    /** All retries failed: park the message and compensate, so the order does not stay PENDING. */
    @Transactional
    public void giveUp(String topic, String key, String payload, String error, OrderPlaced order) {
        deadLetters.save(new DeadLetter(topic, key, payload, error));
        outbox.add(Topics.PAYMENTS, order.orderId(),
                new PaymentResult(order.orderId(), false, "Payment failed after retries: " + error));
    }
}
