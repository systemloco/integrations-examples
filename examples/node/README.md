# LocoAware Webhook Receiver — Node.js (Express)

Reference implementation of a LocoAware webhook receiver in Node.js using Express 4.

## Layout

```
.
├── server.js      ← HTTP receiver: 4 endpoints, HMAC check, enqueues high-volume feeds
├── worker.js      ← Queue consumer: drains the queue and does the real work
├── lib/
│   ├── hmac.js    ← Constant-time HMAC-SHA256 verification
│   ├── queue.js   ← Stand-in queue client (replace with SQS/SNS/Kafka/…)
│   └── repos.js   ← Stand-in repositories for shipments, devices, assets
└── package.json
```

## Run

```bash
npm install
LOCOAWARE_WEBHOOK_SECRET=shhh node server.js
```

In another terminal, run the worker:

```bash
node worker.js
```

Leave `LOCOAWARE_WEBHOOK_SECRET` unset to disable signature verification during development.

## What it does

- **`POST /webhooks/shipments`** — processes inline, responds `200`
- **`POST /webhooks/assets`** — processes inline, responds `200`
- **`POST /webhooks/device-events`** — enqueues, responds `202`
- **`POST /webhooks/device-reports`** — enqueues, responds `202`

Every incoming payload has its HMAC signature verified (when a secret is configured) before anything else happens.

## Tests

```bash
npm install
npm test
```

Uses Node's built-in `node:test` runner — no extra packages required. Tests live in `test/` and cover HMAC verification, all four endpoints, and the stand-in repositories.

## Replacing the stand-ins

Three files are pure stand-ins — look for `TODO:` comments marking the swap points:

- `lib/queue.js` — replace with an SQS/SNS/Kafka/Pub-Sub/Service-Bus/RabbitMQ client
- `lib/repos.js` — replace with your database access layer
- `worker.js` — the consumer loop in here is an in-memory demo; a real worker subscribes to the broker
