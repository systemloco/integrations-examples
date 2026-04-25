# LocoAware Webhook Receiver — Java (Spring Boot)

Reference implementation of a LocoAware webhook receiver in Java using Spring Boot 3.

## Layout

```
.
├── pom.xml
└── src/main/java/com/example/locoaware/
    ├── WebhookApplication.java     ← Spring Boot entry point
    ├── WebhookController.java      ← 4 endpoints, one per feed
    ├── HmacSignatureFilter.java    ← Verifies X-LocoAware-Signature
    ├── QueueClient.java            ← Stand-in queue (swap for SQS/Kafka/…)
    ├── Repositories.java           ← Stand-in shipments/devices/assets repos
    └── DeviceReportWorker.java     ← Drains the queue and does the real work
```

## Run

```bash
mvn spring-boot:run
```

Set the webhook secret via environment variable (omit to disable signing in dev):

```bash
LOCOAWARE_WEBHOOK_SECRET=shhh mvn spring-boot:run
```

## What it does

- **`POST /webhooks/shipments`** — processes inline, responds `200`
- **`POST /webhooks/assets`** — processes inline, responds `200`
- **`POST /webhooks/device-events`** — enqueues, responds `202`
- **`POST /webhooks/device-reports`** — enqueues, responds `202`

The controller uses `JsonNode` for the high-volume feeds so the receiver can enqueue without fully deserialising the payload. The worker is where full POJO binding would happen in a real implementation.

## Tests

```bash
mvn test
```

Tests live under `src/test/java/com/example/locoaware/` and use Spring Boot's `MockMvc` to exercise the four endpoints, HMAC verification, and the stand-in repositories. Replacement beans (declared in `WebhookEndpointsTest.TestConfig`) capture invocations so the tests can assert without Mockito.

## Replacing the stand-ins

Swap out:

- `QueueClient` — use `spring-cloud-aws-messaging` (SQS/SNS), `spring-kafka`, or `spring-rabbit`
- `Repositories` — plug in Spring Data JPA, JDBC, or your persistence layer of choice
- `DeviceReportWorker` — in this example the worker is wired to the in-memory queue via an `@EventListener`; a real worker is a `@SqsListener` / `@KafkaListener` / `@RabbitListener`
