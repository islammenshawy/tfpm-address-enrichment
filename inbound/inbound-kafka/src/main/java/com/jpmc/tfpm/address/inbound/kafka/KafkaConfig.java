package com.jpmc.tfpm.address.inbound.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

/**
 * Kafka infrastructure configuration for the address enrichment listener.
 * Wires a {@link DefaultErrorHandler} with exponential backoff and
 * dead-letter topic (DLT) routing so poison messages don't cause infinite redelivery.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.kafka.enabled", havingValue = "true", matchIfMissing = true)
public class KafkaConfig {

    private static final Logger LOG = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:29092}")
    private String bootstrapServers;

    @Value("${enrichment.kafka.dlt-topic:tfpm.address.enrichment.input.dlt}")
    private String dltTopic;

    @Bean
    public KafkaTemplate<String, String> dltKafkaTemplate() {
        var props = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.ACKS_CONFIG, "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
    }

    @Bean
    public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
            KafkaTemplate<String, String> dltKafkaTemplate) {
        var recoverer = new DeadLetterPublishingRecoverer(
                (KafkaOperations<Object, Object>) (KafkaOperations<?, ?>) dltKafkaTemplate,
                (record, ex) -> new TopicPartition(dltTopic, record.partition()));
        LOG.info("DLT recoverer configured, routing failures to {}", dltTopic);
        return recoverer;
    }

    @Bean
    public CommonErrorHandler kafkaErrorHandler(DeadLetterPublishingRecoverer recoverer) {
        // 3 retries with 1-second fixed backoff, then route to DLT
        var errorHandler = new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 3L));
        errorHandler.addNotRetryableExceptions(
                com.fasterxml.jackson.core.JsonProcessingException.class);
        LOG.info("Kafka error handler configured: 3 retries, 1s backoff, then DLT");
        return errorHandler;
    }
}
