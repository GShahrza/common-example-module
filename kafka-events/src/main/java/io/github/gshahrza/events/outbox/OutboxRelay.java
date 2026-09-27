package io.github.gshahrza.events.outbox;

import io.github.gshahrza.events.Topics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Moves outbox rows to Kafka. A row is marked as published only after the broker confirmed it,
 * so a crash in between sends the message again: at-least-once delivery. Consumers must
 * therefore be idempotent.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository repository;
    private final KafkaTemplate<String, String> kafka;

    public OutboxRelay(OutboxRepository repository, KafkaTemplate<String, String> kafka) {
        this.repository = repository;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${outbox.poll-interval}")
    public void publishPending() {
        for (OutboxEvent event : repository.findTop100ByPublishedAtIsNullOrderByCreatedAt()) {
            try {
                send(event);
                event.markPublished();
                repository.save(event);
            } catch (Exception e) {
                // Kafka is down: keep the row, try again on the next tick. Stop here to keep order.
                log.warn("Outbox publish failed, will retry: {}", e.toString());
                return;
            }
        }
    }

    /** Sends the event and waits for the broker acknowledgement. Also used to replay a duplicate. */
    public void send(OutboxEvent event) throws Exception {
        ProducerRecord<String, String> record =
                new ProducerRecord<>(event.getTopic(), event.getMessageKey(), event.getPayload());
        record.headers().add(Topics.HEADER_EVENT_ID, event.getId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(Topics.HEADER_EVENT_TYPE, event.getType().getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }
}
