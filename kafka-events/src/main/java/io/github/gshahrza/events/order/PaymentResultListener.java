package io.github.gshahrza.events.order;

import io.github.gshahrza.events.Topics;
import io.github.gshahrza.events.Topics.PaymentResult;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class PaymentResultListener {

    private final OrderService service;
    private final JsonMapper jsonMapper;

    PaymentResultListener(OrderService service, JsonMapper jsonMapper) {
        this.service = service;
        this.jsonMapper = jsonMapper;
    }

    @KafkaListener(topics = Topics.PAYMENTS, groupId = "order-service")
    void on(String payload) {
        service.apply(jsonMapper.readValue(payload, PaymentResult.class));
    }
}
