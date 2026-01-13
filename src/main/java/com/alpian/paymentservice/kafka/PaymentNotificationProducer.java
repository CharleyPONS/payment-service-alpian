package com.alpian.paymentservice.kafka;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

@Slf4j
public class PaymentNotificationProducer {

    private final KafkaTemplate<String, PaymentNotification> kafkaTemplate;
    private final String topicName;

    public PaymentNotificationProducer(
            KafkaTemplate<String, PaymentNotification> kafkaTemplate,
            String topicName
    ) {
        this.kafkaTemplate = kafkaTemplate;
        this.topicName = topicName;
    }

    public CompletableFuture<SendResult<String, PaymentNotification>> sendAsync(PaymentNotification notification) {
       return kafkaTemplate.send(
                topicName,
                notification.paymentId().toString(),
                notification
        );
    }
}

