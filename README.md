# LocoAware Data Feeds — Integration Guides & Webhook Examples

Open-source documentation and reference implementations for receiving webhooks from the **LocoAware Integrations** application.

LocoAware can stream data to your systems through four data feeds, each at a different level of abstraction. This repository contains:

- **Integration guides** — full payload reference for each feed
- **Webhook receiver examples** — runnable HTTP handlers in Java, C#, Node, PHP, and Ruby

---

## The four feeds

| Feed | What you receive | Volume | Guide |
|---|---|---|---|
| **Device Reports V2** | Every device report — full state snapshot (sensors, location, observations, time series) | Highest — can spike to hundreds/sec per company | [Device_Reports_V2_Integration_Guide.md](./Device_Reports_V2_Integration_Guide.md) |
| **Device Events** | Alerts and threshold crossings (temperature, impact, zone change, etc.) | Medium | [Device_Events_Integration_Guide.md](./Device_Events_Integration_Guide.md) |
| **Shipments** | Shipment-level events (position reports, exceptions, status changes, notes) | Low | [Shipments_Integration_Guide.md](./Shipments_Integration_Guide.md) |
| **Assets** | Asset-level events | Low | [Assets_Integration_Guide.md](./Assets_Integration_Guide.md) |

If you're not sure which to pick, see the "Which data feed should I choose?" section at the top of any of the guides — they all share the same decision matrix.

---

## Reference webhook receivers

Working examples in five languages. Each one implements all four feeds in a single service, with HMAC signature verification, mock repositories, and a stand-in queue you swap for your real broker.

| Language | Framework | Path |
|---|---|---|
| Node.js | Express | [examples/node/](./examples/node/) |
| Java | Spring Boot | [examples/java/](./examples/java/) |
| C# | ASP.NET Core (minimal API) | [examples/csharp/](./examples/csharp/) |
| PHP | Slim 4 | [examples/php/](./examples/php/) |
| Ruby | Sinatra | [examples/ruby/](./examples/ruby/) |

Read the [examples README](./examples/README.md) for the patterns shared across all of them, including how to [run the test suites](./examples/README.md#running-the-tests). For a step-by-step recipe to point a live LocoAware tenant at a local receiver via ngrok (per language), see [NGROK_TESTING.md](./examples/NGROK_TESTING.md).

---

## Two patterns the examples illustrate

### 1. Always enqueue high-volume feeds

For **Device Reports V2** and **Device Events** the receiver should do the absolute minimum on the request thread: verify the signature, push the payload onto a queue (SQS, SNS, Kafka, Pub/Sub, Service Bus, RabbitMQ, Redis Streams, etc. — or even an `inbox` table in your own database), and respond `202 Accepted`.

A single LocoAware customer with a few thousand active devices can easily produce hundreds of reports per second. Doing the database writes, threshold checks, and downstream calls inline will:

- Time out the webhook (LocoAware retries on non-2xx, multiplying your load)
- Pin your web tier under load spikes
- Make backpressure invisible — failures pile up in the dead letter queue instead of in your monitoring

The examples treat the webhook as a **buffer**: receive, persist to the queue, ack. A separate worker process drains the queue and does the real work, scaling horizontally and independently of the web tier.

For **Shipments** and **Assets** this is far less critical — even a busy operation typically sees only a few of these per minute. Inline processing is fine, though the same enqueue pattern still works if you'd rather keep the architecture uniform.

### 2. Shipment lookup by device name *or* device id

For Device Reports V2 and Device Events, associating an incoming message with a shipment in your system requires looking up the shipment two ways:

1. **By `device.name`** — the convention is to put the shipment ID or shipment name into the device's name field when it's assigned to a shipment. Try this first.
2. **By `device.id`** — fall back to looking up which shipment (if any) currently has this device assigned to it. This catches the case where the device name wasn't set, or where the device is referenced by id rather than name in your system.

The examples wrap this in a single helper, e.g.:

```
shipmentId = repository.findShipmentIdByDeviceNameOrId(deviceName, deviceId)
```

The same shape applies to assets-by-name-or-id when associating asset events with shipments.

If you don't have a system that writes shipment IDs into device names when devices get assigned, **[LocoAlias](https://systemlo.co/apps)** is a LocoAware app that creates a digital twin of a shipment or asset and sets the device's name to match — so the same `device.name` lookup works without you having to build that assignment workflow yourself.

---

## Repository layout

```
.
├── README.md                              ← you are here
├── Device_Reports_V2_Integration_Guide.md
├── Device_Events_Integration_Guide.md
├── Shipments_Integration_Guide.md
├── Assets_Integration_Guide.md
├── docs/
│   └── images/
└── examples/
    ├── README.md
    ├── node/
    ├── java/
    ├── csharp/
    ├── php/
    └── ruby/
```

---

## Contributing

Bug reports and additional language examples are welcome. The examples deliberately avoid project-specific frameworks and use only the most common HTTP framework per language so they're easy to lift into your own stack.

## License

MIT — see [LICENSE](./LICENSE).
