package com.alpian.paymentservice.outbox.model;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "outbox.publisher")
@Getter
@Setter
public class OutboxPublisherProperties {
    int batchSize;
    int maxAttempts;
    long pollIntervalMs;
    long processingTimeoutSeconds;
}

