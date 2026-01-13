package com.alpian.paymentservice.outbox;

import com.alpian.paymentservice.kafka.PaymentNotification;
import com.alpian.paymentservice.kafka.PaymentNotificationProducer;
import com.alpian.paymentservice.persistence.entity.OutboxEventEntity;
import com.alpian.paymentservice.service.OutboxTxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxPublisherScheduler {

    private final OutboxTxService outboxTxService;
    private final PaymentNotificationProducer producer;

    @Scheduled(fixedDelayString = "${outbox.publisher.poll-interval-ms:1000}")
    public void publishLoop() {
        outboxTxService.claimBatch().forEach(this::publishOne);
    }

    private void publishOne(OutboxEventEntity e) {
        UUID outboxId = e.getId();
        int attempt = e.getAttemptCount();

        log.info(
                "Publishing outbox event id={}, aggregateType={}, aggregateId={}, attempt={}",
                outboxId, e.getAggregateType(), e.getAggregateId(), attempt
        );
        PaymentNotification notif;
        try {
            notif = outboxTxService.deserialize(e);
        } catch (Exception ex) {
            outboxTxService.markFailedOrRetry(outboxId, attempt, ex);
            return;
        }

        producer.sendAsync(notif)
                .thenAccept(result -> {
                    log.info(
                            "Outbox event {} successfully published to Kafka (partition={}, offset={})",
                            outboxId,
                            result.getRecordMetadata().partition(),
                            result.getRecordMetadata().offset()
                    );
                    outboxTxService.markSent(outboxId);
                })
                .exceptionally(ex -> {
                    log.warn(
                            "Kafka publish failed for outbox event id={} (attempt={})",
                            outboxId, attempt, ex
                    );
                    outboxTxService.markFailedOrRetry(outboxId, attempt, ex);
                    return null;
                });
    }
}