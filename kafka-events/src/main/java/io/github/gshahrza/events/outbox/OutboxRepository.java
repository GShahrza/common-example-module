package io.github.gshahrza.events.outbox;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {

    List<OutboxEvent> findTop100ByPublishedAtIsNullOrderByCreatedAt();

    Optional<OutboxEvent> findFirstByTypeAndMessageKey(String type, String messageKey);

    List<OutboxEvent> findAllByOrderByCreatedAtDesc();
}
