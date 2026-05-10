package com.jpmc.tfpm.address.inbound.mq;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.config.JmsListenerContainerFactory;
import org.springframework.jms.support.destination.DynamicDestinationResolver;

/**
 * IBM MQ JMS configuration. Creates the {@code jmsListenerContainerFactory}
 * referenced by the {@link AddressEnrichmentMqListener}.
 *
 * <p>The underlying {@link ConnectionFactory} is auto-configured by the
 * IBM MQ Spring Boot starter based on {@code ibm.mq.*} properties in
 * application.yml.
 *
 * <p>Key design decisions:
 * <ul>
 *   <li>Client-ack mode — messages are only acknowledged after Oracle commit.
 *   <li>No XA — the idempotency table provides exactly-once semantics.
 *   <li>MQ's native backout mechanism handles poison messages. When a message
 *       exceeds the backout threshold configured on the queue, MQ routes it
 *       to the designated backout queue (DLQ).
 * </ul>
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.mq.enabled", havingValue = "true", matchIfMissing = true)
public class MQConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MQConfig.class);

    @Value("${spring.jms.listener.concurrency:10}")
    private String concurrency;

    @Value("${spring.jms.listener.max-concurrency:25}")
    private String maxConcurrency;

    @Bean
    public JmsListenerContainerFactory<?> jmsListenerContainerFactory(
            ConnectionFactory connectionFactory) {
        var factory = new DefaultJmsListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setSessionAcknowledgeMode(Session.CLIENT_ACKNOWLEDGE);
        factory.setConcurrency(concurrency + "-" + maxConcurrency);
        factory.setDestinationResolver(new DynamicDestinationResolver());
        factory.setErrorHandler(t ->
                LOG.error("JMS listener error (message will be redelivered by MQ backout): {}",
                        t.getMessage(), t));
        LOG.info("JMS listener container factory configured: ack=CLIENT, concurrency={}-{}",
                concurrency, maxConcurrency);
        return factory;
    }
}
