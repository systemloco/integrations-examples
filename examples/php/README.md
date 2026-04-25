# LocoAware Webhook Receiver — PHP (Slim 4)

Reference implementation of a LocoAware webhook receiver in PHP using Slim 4.

## Layout

```
.
├── composer.json
├── public/
│   └── index.php           ← Slim app entry point, 4 endpoints, HMAC middleware
└── src/
    ├── QueueClient.php     ← Stand-in queue (swap for SQS/RabbitMQ/…)
    └── Repositories.php    ← Stand-in shipments/devices/assets repos
```

## Run

```bash
composer install
LOCOAWARE_WEBHOOK_SECRET=shhh php -S localhost:8080 -t public
```

## What it does

- **`POST /webhooks/shipments`** — processes inline, responds `200`
- **`POST /webhooks/assets`** — processes inline, responds `200`
- **`POST /webhooks/device-events`** — enqueues, responds `202`
- **`POST /webhooks/device-reports`** — enqueues, responds `202`

## Tests

```bash
composer install
vendor/bin/phpunit
```

PHPUnit suite under `tests/` covers HMAC verification, the four endpoints, and the stand-in repositories. Tests dispatch PSR-7 requests through the Slim app in-process — no HTTP server required.

## Replacing the stand-ins

- `QueueClient` — swap for `aws/aws-sdk-php` (SQS/SNS), `php-amqplib/php-amqplib` (RabbitMQ), `pda/pheanstalk` (beanstalkd), or a Redis Streams client
- `Repositories` — replace with PDO / Doctrine / Eloquent / your ORM

## Note on worker processes

PHP-FPM worker processes die between requests, so the in-process queue demoed here only works for testing with a single request at a time. In production:

1. The webhook endpoint pushes to a real broker (SQS, Kafka, RabbitMQ, etc.).
2. A separate long-running worker process consumes from the broker — typically run via `supervisord`, systemd, Symfony Messenger, Laravel Queue workers, or similar.
