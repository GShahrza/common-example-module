package io.github.gshahrza.events;

/** Topic names and the event payloads that travel through them. */
public final class Topics {

    /** Order service publishes here, payment service listens. Key = order id. */
    public static final String ORDERS = "orders";
    /** Payment service publishes here, order service listens. Key = order id. */
    public static final String PAYMENTS = "payments";

    public static final String HEADER_EVENT_ID = "eventId";
    public static final String HEADER_EVENT_TYPE = "eventType";

    public record OrderPlaced(long orderId, String customer, java.math.BigDecimal amount) {
    }

    public record PaymentResult(long orderId, boolean success, String reason) {
    }

    private Topics() {
    }
}
