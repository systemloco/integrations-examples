package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Stand-in queue client.
 *
 * TODO: swap this for a real broker. Options:
 *   - AWS SQS / SNS      spring-cloud-aws-messaging
 *   - Apache Kafka       spring-kafka
 *   - RabbitMQ           spring-rabbit
 *   - Google Pub/Sub     spring-cloud-gcp-pubsub
 *   - Azure Service Bus  azure-messaging-servicebus
 *   - Or an `inbox` table drained by a scheduled worker.
 *
 * The controller only calls {@link #publish(String, JsonNode)} and expects it
 * to return once the message is durably persisted. Do not ack the HTTP request
 * before this returns — if the JVM dies in between you lose the message.
 */
@Component
public class QueueClient {

    private final ApplicationEventPublisher bus;

    public QueueClient(ApplicationEventPublisher bus) {
        this.bus = bus;
    }

    public void publish(String topic, JsonNode message) {
        bus.publishEvent(new QueueMessage(topic, message));
    }

    public record QueueMessage(String topic, JsonNode body) {}
}
