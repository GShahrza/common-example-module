package io.github.gshahrza.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.gshahrza.events.Topics.OrderPlaced;
import io.github.gshahrza.events.order.Order;
import io.github.gshahrza.events.outbox.Outbox;
import io.github.gshahrza.events.payment.PaymentService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.web.client.RestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "outbox.poll-interval=100ms"})
@EmbeddedKafka(partitions = 1)
class KafkaEventsTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @LocalServerPort
    int port;
    @Autowired
    Outbox outbox;
    @Autowired
    PaymentService payments;

    RestClient http() {
        return RestClient.builder().baseUrl("http://localhost:" + port)
                .defaultStatusHandler(HttpStatusCode::isError, (req, res) -> { })
                .build();
    }

    Order place(String amount) {
        return http().post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customer", "Aynur", "amount", new BigDecimal(amount)))
                .retrieve().body(Order.class);
    }

    Order awaitFinished(long id) {
        return await().atMost(TIMEOUT).until(
                () -> http().get().uri("/api/orders/{id}", id).retrieve().body(Order.class),
                o -> o.getStatus() != Order.Status.PENDING);
    }

    List<Map<String, Object>> list(String uri) {
        return http().get().uri(uri).retrieve().body(new ParameterizedTypeReference<>() { });
    }

    long paymentsFor(long orderId) {
        return list("/api/payments").stream()
                .filter(p -> ((Number) p.get("orderId")).longValue() == orderId).count();
    }

    @Test
    void orderIsAcceptedAsPendingAndConfirmedAfterPayment() {
        Order order = place("100");
        assertThat(order.getStatus()).isEqualTo(Order.Status.PENDING);

        assertThat(awaitFinished(order.getId()).getStatus()).isEqualTo(Order.Status.CONFIRMED);
        assertThat(paymentsFor(order.getId())).isEqualTo(1);
    }

    @Test
    void declinedPaymentCompensatesTheOrder() {
        Order order = awaitFinished(place("5000").getId());

        assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(order.getReason()).contains("limit");
    }

    @Test
    void duplicateEventIsNotChargedTwice() {
        long id = awaitFinished(place("250").getId()).getId();
        long before = payments.duplicatesSkipped();

        http().post().uri("/api/orders/{id}/replay", id).retrieve().toBodilessEntity();

        await().atMost(TIMEOUT).until(() -> payments.duplicatesSkipped() > before);
        assertThat(paymentsFor(id)).isEqualTo(1);
    }

    @Test
    void transientFailureIsRetriedThenParkedInTheDeadLetterTopic() {
        Order order = awaitFinished(place("13").getId());

        assertThat(order.getStatus()).isEqualTo(Order.Status.CANCELLED);
        assertThat(order.getReason()).contains("after retries");
        assertThat(list("/api/dead-letters")).anySatisfy(d -> {
            assertThat(d.get("messageKey")).isEqualTo(String.valueOf(order.getId()));
            assertThat(d.get("topic")).isEqualTo(Topics.ORDERS);
        });
        assertThat(paymentsFor(order.getId())).isZero();
    }

    @Test
    void outboxRefusesToWorkOutsideATransaction() {
        assertThatThrownBy(() -> outbox.add(Topics.ORDERS, 1, new OrderPlaced(1, "x", BigDecimal.ONE)))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void invalidOrderIsRejected() {
        var response = http().post().uri("/api/orders").contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("customer", "", "amount", -5)).retrieve().toBodilessEntity();
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}
