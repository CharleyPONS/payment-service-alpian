package com.alpian.paymentservice.service;

import com.alpian.paymentservice.kafka.PaymentNotification;
import com.alpian.paymentservice.outbox.model.OutboxPublisherProperties;
import com.alpian.paymentservice.persistence.entity.EventStatus;
import com.alpian.paymentservice.persistence.entity.EventType;
import com.alpian.paymentservice.persistence.entity.OutboxEventEntity;
import com.alpian.paymentservice.persistence.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class OutboxTxService {

    private final OutboxRepository outboxRepository;
    private final OutboxPublisherProperties props;
    private final JsonService jsonService;

    @Transactional
    public List<OutboxEventEntity> claimBatch() {
        var outboxEvents = outboxRepository.lockNextBatchRecoverable(EventType.PAYMENT_CREATED.name(), props.getBatchSize(), props.getProcessingTimeoutSeconds());
        if (outboxEvents.isEmpty()) return List.of();

        var outboxIds = outboxEvents.stream().map(OutboxEventEntity::getId).toList();
        outboxRepository.markProcessingAndIncrementAttempt(outboxIds);

        for (var e : outboxEvents) {
            e.setEventStatus(EventStatus.PROCESSING);
            e.setAttemptCount(e.getAttemptCount() + 1);
        }
        return outboxEvents;
    }

    public PaymentNotification deserialize(OutboxEventEntity e) {
        return jsonService.fromJson(e.getPayload(), PaymentNotification.class);
    }

    @Transactional
    public void markSent(UUID outboxId) {
        outboxRepository.updateStatusSent(outboxId, Instant.now());
    }

    @Transactional
    public void markFailedOrRetry(UUID outboxId, int attemptCount, Throwable ex) {
        String err = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());

        if (attemptCount >= props.getMaxAttempts()) {
            outboxRepository.updateStatusFailed(outboxId, attemptCount, err, Instant.now());
            log.error("Outbox event {} marked FAILED after {} attempts", outboxId, attemptCount);
        } else {
            outboxRepository.updateStatusRetry(outboxId, attemptCount, err);
            log.warn("Outbox event {} will retry (attempt {}/{})", outboxId, attemptCount, props.getMaxAttempts());
        }
    }
}

