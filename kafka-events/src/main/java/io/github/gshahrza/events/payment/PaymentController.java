package io.github.gshahrza.events.payment;

import io.github.gshahrza.events.outbox.OutboxEvent;
import io.github.gshahrza.events.outbox.OutboxRepository;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only views for the demo page: what happened behind the scenes. */
@RestController
class PaymentController {

    private final PaymentRepository payments;
    private final DeadLetterRepository deadLetters;
    private final ProcessedMessageRepository processed;
    private final OutboxRepository outbox;
    private final PaymentService service;

    PaymentController(PaymentRepository payments, DeadLetterRepository deadLetters,
                      ProcessedMessageRepository processed, OutboxRepository outbox,
                      PaymentService service) {
        this.payments = payments;
        this.deadLetters = deadLetters;
        this.processed = processed;
        this.outbox = outbox;
        this.service = service;
    }

    @GetMapping("/api/stats")
    Map<String, Long> stats() {
        return Map.of("duplicatesSkipped", service.duplicatesSkipped());
    }

    @GetMapping("/api/payments")
    List<Payment> payments() {
        return payments.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @GetMapping("/api/dead-letters")
    List<DeadLetter> deadLetters() {
        return deadLetters.findAll(Sort.by(Sort.Direction.DESC, "id"));
    }

    @GetMapping("/api/processed")
    List<ProcessedMessage> processed() {
        return processed.findAll();
    }

    @GetMapping("/api/outbox")
    List<OutboxEvent> outbox() {
        return outbox.findAllByOrderByCreatedAtDesc();
    }
}
