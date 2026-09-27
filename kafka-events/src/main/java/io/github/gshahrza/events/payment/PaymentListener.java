package io.github.gshahrza.events.payment;

import io.github.gshahrza.events.Topics;
import io.github.gshahrza.events.Topics.OrderPlaced;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.BackOff;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Non-blocking retries: a failed message goes to orders-retry-0, then orders-retry-1, each with a
 * delay, and finally to orders-dlt. The main topic keeps flowing meanwhile.
 */
@Component
class PaymentListener {

    private final PaymentService service;
    private final JsonMapper jsonMapper;

    PaymentListener(PaymentService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @RetryableTopic(
            attempts = "3",
            backOff = @BackOff(delay = 500, multiplier = 2),
            include = PaymentGatewayException.class,
            dltStrategy = DltStrategy.FAIL_ON_ERROR)
    @KafkaListener(topics = Topics.ORDERS, groupId = "payment-service")
    void on(String payload, @Header(Topics.HEADER_EVENT_ID) String eventId) {
        service.charge(eventId, jsonMapper.readValue(payload, OrderPlaced.class));
    }

    @DltHandler
    void dlt(ConsumerRecord<String, String> record) {
        String topic = header(record, KafkaHeaders.ORIGINAL_TOPIC);
        // The error of the last attempt, e.g. "Listener failed; Payment gateway timeout for order 7"
        String error = String.valueOf(header(record, KafkaHeaders.EXCEPTION_MESSAGE)).replace("Listener failed; ", "");
        service.giveUp(topic, record.key(), record.value(), error,
                jsonMapper.readValue(record.value(), OrderPlaced.class));
    }

    private static String header(ConsumerRecord<?, ?> record, String name) {
        var header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
