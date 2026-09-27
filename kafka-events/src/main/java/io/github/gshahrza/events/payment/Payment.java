package io.github.gshahrza.events.payment;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
public class Payment {

    @Id
    @GeneratedValue
    private Long id;
    private long orderId;
    private BigDecimal amount;
    private boolean success;
    private String reason;
    private Instant createdAt;

    protected Payment() {
    }

    Payment(long orderId, BigDecimal amount, boolean success, String reason) {
        this.orderId = orderId;
        this.amount = amount;
        this.success = success;
        this.reason = reason;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public long getOrderId() { return orderId; }
    public BigDecimal getAmount() { return amount; }
    public boolean isSuccess() { return success; }
    public String getReason() { return reason; }
    public Instant getCreatedAt() { return createdAt; }
}
