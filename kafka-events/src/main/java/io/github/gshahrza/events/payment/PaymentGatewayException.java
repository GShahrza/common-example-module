package io.github.gshahrza.events.payment;

/** A temporary failure (timeout, 503). Worth retrying, unlike a declined card. */
public class PaymentGatewayException extends RuntimeException {

    public PaymentGatewayException(String message) {
        super(message);
    }
}
