# Webhook Receiver Examples

Reference implementations of a LocoAware webhook receiver in five languages. Each example is a single, runnable HTTP service that handles all four data feeds.

| Language | Framework | Path |
|---|---|---|
| Node.js | Express 4 | [node/](./node/) |
| Java | Spring Boot 3 | [java/](./java/) |
| C# | ASP.NET Core 8 (minimal API) | [csharp/](./csharp/) |
| PHP | Slim 4 | [php/](./php/) |
| Ruby | Sinatra 4 | [ruby/](./ruby/) |

All five implement the same shape, so you can read whichever one is closest to your stack and translate. Pick one, copy it into your service, and swap the stand-ins for your real infrastructure.

---

## What each example demonstrates

Each example exposes four endpoints — one per data feed:

| Endpoint | Feed | Pattern |
|---|---|---|
| `POST /webhooks/shipments` | [Shipments](../Shipments_Integration_Guide.md) | Process inline, respond `200` |
| `POST /webhooks/assets` | [Assets](../Assets_Integration_Guide.md) | Process inline, respond `200` |
| `POST /webhooks/device-events` | [Device Events](../Device_Events_Integration_Guide.md) | Enqueue, respond `202` |
| `POST /webhooks/device-reports` | [Device Reports V2](../Device_Reports_V2_Integration_Guide.md) | Enqueue, respond `202` |

Each one also includes:

- **HMAC signature verification** — constant-time comparison against a shared secret (`LOCOAWARE_WEBHOOK_SECRET`)
- **A Device Reports V2 parser** — `DeviceReportParser.parse(report)` turns a webhook payload into a document-store-shaped record: `_id` keyed to the report, every documented subsection extracted into a named field, optional fields omitted entirely (no explicit `null`s), and a derived `role` of `"primary" | "secondary" | "standalone"` so the primary↔secondary relationship between a LocoTrack hub and the BLE LocoTags it relays is explicit. Tables and schema migrations are deliberately out of scope.
- **An in-memory document-store stand-in** — `deviceReports`, `devices`, `shipments`, `assets` collections, each a `Map<id, document>` shaped the way you'd push it into MongoDB / DocumentDB / CosmosDB / Couchbase / Firestore. Each method's docstring includes the production call it stands in for (`collection.updateOne(...{ upsert: true })`, etc.).
- **A mock queue client** — `publish(topic, message)`. **This is for illustration only.** In real life, replace with SQS / SNS / Kafka / Pub/Sub / Service Bus / RabbitMQ / Redis Streams, or an `inbox` database table drained by a background worker.
- **A queue-draining worker** — shows what the consumer does on every dequeued message: insert into immutable `device_reports` history, append to the matched shipment, upsert per-device latest state, and (for primaries) presence-touch each paired secondary without clobbering the tag's authoritative sensor data.

---

## The two patterns that matter

### 1. Enqueue the high-volume feeds

**Device Reports V2** and **Device Events** can spike to hundreds of messages per second for a single company. The receiver must do the minimum on the request thread:

```
verify signature → enqueue payload → respond 202
```

All real work (database writes, shipment lookup, downstream calls) happens in a separate worker process. This keeps the web tier cheap and responsive, and lets the worker scale horizontally without affecting ingest.

**Shipments** and **Assets** are low-volume. Inline processing is fine — though the enqueue pattern still works if you'd rather keep things uniform.

### 2. Shipment lookup by device/asset name *or* id

When associating a Device Reports V2 or Device Events message with a shipment in your system, the lookup must be flexible:

- **Try `device.name` first.** Conventionally this holds the shipment ID or name when the device is assigned to a shipment.
- **Fall back to `device.id`.** Find which shipment currently has this device assigned. This catches the case where `device.name` wasn't set, or where your system associates shipments with devices directly rather than via name.

The same shape applies to `asset.name` / `asset.id` for the Assets feed.

Each example wraps this in a helper:

```
shipmentId = repository.findShipmentIdByDeviceNameOrId(name, id)
```

The helper's internal logic (a single indexed SQL query, a cached Redis lookup, etc.) is up to you — what matters is that the caller asks for "the shipment associated with *either* this name *or* this device id" in one call.

#### Tag (secondary) reports — fall back to the primary

When a BLE tag paired to a cellular/Wi-Fi gateway reports in, you receive **two distinct messages** per reporting window: one for the gateway (the **primary**), and a separate one for each tag (the **secondary**), where `reportedBy.device` on the tag's report points back at the primary that relayed it. See [Primary and Secondary Reporting](../Device_Reports_V2_Integration_Guide.md#9-primary-and-secondary-reporting) in the V2 guide for the full payload shape.

The worker examples handle this two-pass:

1. **Shipment lookup falls back through the relay.** If a tag's own `device.name`/`device.id` doesn't match a shipment, the worker re-runs the lookup against `reportedBy.device.name` / `reportedBy.device.id`. Tags rarely have their own shipment binding — falling back to the primary attaches the tag's sensor data to the same shipment as the gateway it lives on.
2. **Primary reports presence-touch each observed secondary.** When the primary's report has `secondaryObservations[]`, the worker iterates that list and calls `devices.touchObservation(secondaryId, …)` on each observed tag — writing only `lastSeenBy` (primary device id, RSSI, observation time). The tag's authoritative sensor data still arrives in its own standalone report, which writes `lastSensors` / `lastLocation` separately. The two writes commute: whichever message arrives second never overwrites the other's fields.

#### Don't have your own dockside / device-assignment workflow?

The `device.name → shipment` convention assumes you have something on your end that writes the shipment ID into the device's name when it's assigned. If you don't, **[LocoAlias](https://systemlo.co/apps)** is a LocoAware app that creates a digital twin of a shipment or asset and sets the device's name to match — so the same `device.name` lookup works without you having to build that assignment workflow yourself.

---

## Running the examples

Each example has its own README with language-specific setup and run instructions. All of them:

- Start an HTTP server on port `8080` by default
- Read `LOCOAWARE_WEBHOOK_SECRET` from the environment (leave unset to disable signature checks during development)
- Log every received event to stdout so you can confirm delivery end-to-end

### Testing with curl

```bash
# Shipment event
curl -X POST http://localhost:8080/webhooks/shipments \
  -H 'Content-Type: application/json' \
  -d @sample-shipment-event.json

# Device report (gets enqueued, you'll see the worker log pick it up)
curl -X POST http://localhost:8080/webhooks/device-reports \
  -H 'Content-Type: application/json' \
  -d @sample-device-report.json
```

### Testing with signed requests

```bash
BODY=$(cat sample-shipment-event.json)
# LocoAware sends base64(HMAC-SHA256(body, secret)) — match that format here.
SIG=$(printf '%s' "$BODY" | openssl dgst -sha256 -hmac "$LOCOAWARE_WEBHOOK_SECRET" -binary | base64)

curl -X POST http://localhost:8080/webhooks/shipments \
  -H 'Content-Type: application/json' \
  -H "X-LocoAware-Signature: $SIG" \
  -d "$BODY"
```

---

---

## Running the tests

Every example ships with a small test suite covering HMAC verification, the four endpoints, and the stand-in repositories. The tests are independent — you can run them per language, or all at once via a wrapper script.

### All five at once

```bash
./examples/run-all-tests.sh
```

The script auto-detects which language toolchains are installed, runs each suite that it can, and prints a `PASS / FAIL / SKIP` summary at the end. Suites whose toolchain isn't installed are reported as SKIP and do not fail the script — install the missing toolchain to opt them in.

### Toolchain dependencies

You only need the toolchain for the language(s) you want to exercise. Each row is independent:

| Language | Required to run tests |
|---|---|
| Node.js | `node` ≥ 18 and `npm` (built-in `node:test` runner — no extra packages) |
| Java | `mvn` (Maven) and a JDK ≥ 17 |
| C# | `dotnet` SDK 8 or 9 (the project multi-targets `net8.0;net9.0` — whichever runtime is present is used) |
| PHP | `php` ≥ 8.2 and `composer` (PHPUnit installed as a dev dependency) |
| Ruby | `ruby` ≥ 3.1 and `bundler` (Sinatra 4 requires Ruby 3.1+) |

### Running a single language's tests

Each suite stands alone — run it directly without the wrapper if you only care about one stack:

```bash
# Node.js
cd examples/node && npm install && npm test

# Java
cd examples/java && mvn test

# C#
cd examples/csharp/LocoAware.Webhook.Tests && dotnet test

# PHP
cd examples/php && composer install && vendor/bin/phpunit

# Ruby
cd examples/ruby && bundle install && bundle exec rake test
```

Each example's `README.md` has more detail on its specific test layout.

---

## Connecting a live LocoAware tenant via ngrok (development only)

Once a receiver runs locally and its tests pass, the next sanity check is to point a real LocoAware tenant at it and watch payloads arrive. The easiest way is to expose your local port through [ngrok](https://ngrok.com/).

> 📘 **For copy-paste recipes per language, see [NGROK_TESTING.md](./NGROK_TESTING.md).** It walks through every step (install, sign up for ngrok, run the receiver, sanity-check with curl, wire it into LocoAware) for each of the five examples, plus a troubleshooting table. Skim the section below for the shape; jump to the deep dive when you actually want to do it.

> ⚠️ **Development use only.** This setup is for poking at real payloads on your laptop while you're building the integration. **Do not** put an ngrok URL into a production LocoAware tenant — ngrok tunnels are ephemeral, unauthenticated by default, and bypass any network controls you'd normally have in front of your service. Once you're ready, deploy the receiver behind your own TLS-terminated public endpoint with HMAC verification enabled.

### Steps

1. **Pick an example and start it on `:8080`** (with a webhook secret set so HMAC verification is exercised end-to-end):

   ```bash
   # Node example
   cd examples/node && npm install
   LOCOAWARE_WEBHOOK_SECRET=shhh-dev-secret node server.js
   ```

   Equivalent commands for the other languages live in each example's README.

2. **Open an ngrok tunnel to `:8080`** in a second terminal:

   ```bash
   ngrok http 8080
   ```

   ngrok prints a `https://<random>.ngrok-free.app` URL — that's the public URL LocoAware will POST to.

3. **Add the URL to LocoAware.** In the LocoAware Integrations app, create or edit a webhook destination and set:

   - **URL:** `https://<your-ngrok-id>.ngrok-free.app/webhooks/device-reports` (or `/webhooks/shipments`, `/webhooks/assets`, `/webhooks/device-events` — one webhook per feed you want to subscribe to)
   - **Secret:** the same value you set as `LOCOAWARE_WEBHOOK_SECRET` above
   - **Signature header:** leave at the default `X-LocoAware-Signature`

4. **Watch payloads come through.** stdout in your example process logs every received event. The ngrok dashboard at <http://127.0.0.1:4040> shows each request, headers, and body — handy for inspecting what LocoAware actually sends.

### Tearing down

When you're done, remove the webhook destination from the LocoAware tenant (or pause it), then `Ctrl-C` ngrok and the example process. Don't leave a tunnel running and registered against a live tenant — restart your laptop and that URL becomes a black hole that LocoAware will keep retrying until the destination is removed.

---

## Production checklist

Before pointing a real feed at your receiver:

- [ ] HMAC verification is enabled (`LOCOAWARE_WEBHOOK_SECRET` set, signature header checked)
- [ ] Constant-time comparison is used for the signature (don't use `==`)
- [ ] High-volume feeds enqueue rather than process inline
- [ ] The queue itself is durable (messages survive worker restart)
- [ ] The worker is idempotent — LocoAware may deliver a message more than once on retry
- [ ] 2xx is returned only **after** the payload is durably received (enqueued, or written to your database)
- [ ] Non-2xx responses are logged so you can correlate with the sender's dead-letter queue
- [ ] You handle backpressure — if the queue fills up, return 5xx and let LocoAware retry, rather than dropping the payload
