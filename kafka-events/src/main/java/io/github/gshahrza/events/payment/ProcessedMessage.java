package io.github.gshahrza.events.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;

/**
 * "I have already handled this event." The primary key is the event id, so even two parallel
 * deliveries of the same event cannot both be committed: the second insert fails.
 */
@Entity
public class ProcessedMessage {

    @Id
    private String eventId;
    private Instant processedAt;

    protected ProcessedMessage() {
    }

    ProcessedMessage(String eventId) {
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }

    public String getEventId() { return eventId; }
    public Instant getProcessedAt() { return processedAt; }
}
