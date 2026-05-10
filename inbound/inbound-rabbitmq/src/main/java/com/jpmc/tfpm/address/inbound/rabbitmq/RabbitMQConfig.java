package com.jpmc.tfpm.address.inbound.rabbitmq;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ infrastructure configuration. Creates queue, DLQ, exchange,
 * and bindings. Configurable on/off via {@code enrichment.rabbitmq.enabled}.
 *
 * <p>Manual ack mode: messages acknowledged after Oracle commit.
 * Failed messages nack'd to DLQ (dead-letter exchange routing).
 */
@Configuration
@ConditionalOnProperty(name = "enrichment.rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {

    private static final Logger LOG = LoggerFactory.getLogger(RabbitMQConfig.class);

    private final String inputQueue;
    private final String dlqName;
    private final String outputQueue;

    public RabbitMQConfig(org.springframework.core.env.Environment env) {
        this.inputQueue = env.getProperty("enrichment.rabbitmq.input-queue", "tfpm.address.enrichment.input");
        this.dlqName = env.getProperty("enrichment.rabbitmq.dlq", "tfpm.address.enrichment.input.dlq");
        this.outputQueue = env.getProperty("enrichment.rabbitmq.output-queue", "tfpm.address.enrichment.output");
    }

    @Bean
    public Queue enrichmentInputQueue() {
        return QueueBuilder.durable(inputQueue)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", dlqName)
                .build();
    }

    @Bean
    public Queue enrichmentDlq() {
        return QueueBuilder.durable(dlqName).build();
    }

    @Bean
    public Queue enrichmentOutputQueue() {
        return QueueBuilder.durable(outputQueue).build();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        var template = new RabbitTemplate(connectionFactory);
        template.setDefaultReceiveQueue(inputQueue);
        LOG.info("RabbitMQ configured: input={}, output={}, dlq={}", inputQueue, outputQueue, dlqName);
        return template;
    }
}
