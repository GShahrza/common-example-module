package io.github.gshahrza.events.outbox;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/** Business code calls this instead of KafkaTemplate. */
@Component
public class Outbox {

    private final OutboxRepository repository;
    private final JsonMapper jsonMapper;

    public Outbox(OutboxRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * MANDATORY: calling this outside a transaction is a bug, because the event could then be
     * saved without the business change (or the other way round).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void add(String topic, Object key, Object event) {
        repository.save(new OutboxEvent(topic, String.valueOf(key), event.getClass().getSimpleName(),
                jsonMapper.writeValueAsString(event)));
    }
}
