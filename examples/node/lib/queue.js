// TODO: swap this stand-in for your real broker.
//
// Production options:
//   - AWS SQS / SNS        @aws-sdk/client-sqs, @aws-sdk/client-sns
//   - Google Pub/Sub       @google-cloud/pubsub
//   - Azure Service Bus    @azure/service-bus
//   - Apache Kafka         kafkajs
//   - RabbitMQ             amqplib
//   - Redis Streams        ioredis
//   - Or: an `inbox` table in your own database, drained by a polling worker.
//
// The receiver only needs `publish(topic, message)` to return a promise that
// resolves once the message is DURABLY persisted. Don't ack the webhook before
// the publish resolves — if the process dies between the two you lose the
// message and LocoAware won't redeliver it (you already said 2xx).

const EventEmitter = require('events');
const bus = new EventEmitter();
const inMemoryQueues = new Map();

async function publish(topic, message) {
    if (!inMemoryQueues.has(topic)) inMemoryQueues.set(topic, []);
    inMemoryQueues.get(topic).push(message);
    bus.emit(topic, message);
}

function subscribe(topic, handler) {
    bus.on(topic, async (message) => {
        try {
            await handler(message);
        } catch (err) {
            console.error(`[worker] ${topic} handler failed:`, err);
            // Real broker: don't ack → message goes back on the queue / DLQ.
        }
    });
}

module.exports = { publish, subscribe };
