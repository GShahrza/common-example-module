package io.github.gshahrza.events.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.time.Instant;

/** A message that failed all retries. Kept for a human to inspect and replay. */
@Entity
public class DeadLetter {

    @Id
    @GeneratedValue
    private Long id;
    private String topic;
    private String messageKey;
    @Column(length = 4000)
    private String payload;
    @Column(length = 1000)
    private String error;
    private Instant createdAt;

    protected DeadLetter() {
    }

    DeadLetter(String topic, String messageKey, String payload, String error) {
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
        this.error = error;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getTopic() { return topic; }
    public String getMessageKey() { return messageKey; }
    public String getPayload() { return payload; }
    public String getError() { return error; }
    public Instant getCreatedAt() { return createdAt; }
}
