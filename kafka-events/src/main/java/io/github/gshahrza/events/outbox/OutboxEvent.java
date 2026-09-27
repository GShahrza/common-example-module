package io.github.gshahrza.events.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import java.time.Instant;
import java.util.UUID;

/**
 * A message waiting to be published. It is written in the same database transaction as the
 * business change, so either both exist or neither does.
 */
@Entity
public class OutboxEvent {

    @Id
    private UUID id;
    private String topic;
    private String messageKey;
    private String type;
    @Column(length = 4000)
    private String payload;
    private Instant createdAt;
    private Instant publishedAt;

    protected OutboxEvent() {
    }

    OutboxEvent(String topic, String messageKey, String type, String payload) {
        this.id = UUID.randomUUID();
        this.topic = topic;
        this.messageKey = messageKey;
        this.type = type;
        this.payload = payload;
        this.createdAt = Instant.now();
    }

    void markPublished() {
        this.publishedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
}
