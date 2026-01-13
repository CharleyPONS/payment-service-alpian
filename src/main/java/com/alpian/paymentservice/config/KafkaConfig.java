package com.alpian.paymentservice.config;

import com.alpian.paymentservice.kafka.PaymentNotification;
import com.alpian.paymentservice.kafka.PaymentNotificationProducer;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;


@Configuration
@EnableConfigurationProperties(KafkaTopicProperties.class)
@RequiredArgsConstructor
public class KafkaConfig {
    private final KafkaTopicProperties kafkaTopicProperties;

    @Bean NewTopic paymentNotificationTopic(KafkaAdmin kafkaAdmin) {
        return TopicBuilder.name(kafkaTopicProperties.getPaymentNotification())
                .partitions(4)
                .replicas(1)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                .build();
    }

    /**
     * Initialisation personnalisée du producteur Kafka avec injection dynamique du nom de topic.
     * @param kafkaProducerFactory
     * @param paymentNotificationTopic
     * @return PaymentNotificationProducer
     */
    @Bean
    public PaymentNotificationProducer paymentNotificationProducer(
            ProducerFactory<String, PaymentNotification> kafkaProducerFactory,
            NewTopic paymentNotificationTopic
    ) {
        Map<String, Object> overrideProps = Map.of(
                ProducerConfig.MAX_REQUEST_SIZE_CONFIG, kafkaTopicProperties.getRequestMaxSize(),
                ProducerConfig.COMPRESSION_TYPE_CONFIG, kafkaTopicProperties.getCompressionType());
        var producerfactory = kafkaProducerFactory.copyWithConfigurationOverride(overrideProps);
        var kafkaTemplate = new KafkaTemplate<>(producerfactory);
        return new PaymentNotificationProducer(
                kafkaTemplate,
                paymentNotificationTopic.name()
        );
    }
}