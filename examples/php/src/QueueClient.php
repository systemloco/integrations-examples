<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

/**
 * Stand-in queue client.
 *
 * TODO: swap for your real broker:
 *   - AWS SQS / SNS        aws/aws-sdk-php
 *   - RabbitMQ             php-amqplib/php-amqplib
 *   - beanstalkd           pda/pheanstalk
 *   - Redis Streams        predis/predis
 *   - Or: Symfony Messenger / Laravel Queue + an `inbox` DB table
 *
 * publish() must return once the message is DURABLY persisted. Do not ack
 * the webhook response before publish() returns — if the PHP process dies
 * in between you lose the message and LocoAware won't redeliver it.
 */
class QueueClient
{
    public function publish(string $topic, string $rawBody): void
    {
        // Real implementation: SqsClient::sendMessage, AMQPChannel::basic_publish, etc.
        error_log("[queue] publish → {$topic} (" . strlen($rawBody) . " bytes)");
    }
}
