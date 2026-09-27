package io.github.gshahrza.events.payment;

import org.springframework.data.jpa.repository.JpaRepository;

interface PaymentRepository extends JpaRepository<Payment, Long> {

    long countByOrderId(long orderId);
}

interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, String> {
}

interface DeadLetterRepository extends JpaRepository<DeadLetter, Long> {
}
