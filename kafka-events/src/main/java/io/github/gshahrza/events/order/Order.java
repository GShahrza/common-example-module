package io.github.gshahrza.events.order;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    public enum Status { PENDING, CONFIRMED, CANCELLED }

    @Id
    @GeneratedValue
    private Long id;
    private String customer;
    private BigDecimal amount;
    @Enumerated(EnumType.STRING)
    private Status status;
    private String reason;
    private Instant updatedAt;

    protected Order() {
    }

    Order(String customer, BigDecimal amount) {
        this.customer = customer;
        this.amount = amount;
        this.status = Status.PENDING;
        this.updatedAt = Instant.now();
    }

    /**
     * Only a PENDING order can change. A duplicate or late PaymentResult is therefore harmless:
     * the transition is naturally idempotent.
     */
    boolean complete(boolean paid, String reason) {
        if (status != Status.PENDING) {
            return false;
        }
        this.status = paid ? Status.CONFIRMED : Status.CANCELLED;
        this.reason = reason;
        this.updatedAt = Instant.now();
        return true;
    }

    public Long getId() { return id; }
    public String getCustomer() { return customer; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public String getReason() { return reason; }
    public Instant getUpdatedAt() { return updatedAt; }
}
