package com.jpmc.tfpm.address.inbound.mq;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * <p>Client-ack mode, no XA — idempotency table provides exactly-once.
 * MQ's native backout mechanism handles poison messages.
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.mq.enabled", havingValue = "true", matchIfMissing = true)
public class MQConfig {

    private static final Logger LOG = LoggerFactory.getLogger(MQConfig.class);

    private final String concurrency;
    private final String maxConcurrency;

    public MQConfig(org.springframework.core.env.Environment env) {
        this.concurrency = env.getProperty("spring.jms.listener.concurrency", "10");
        this.maxConcurrency = env.getProperty("spring.jms.listener.max-concurrency", "25");
    }

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
