# LocoAware Shipments Integration Guide

This guide is for customers integrating their systems with LocoAware shipment data via the **Integrations** application. It describes the **Shipments** data feed — shipment-level events such as position reports, exceptions, status changes, notes, and shipment create/update — including the payload structure, every event type, and how to receive events in your own service.

## Which data feed should I choose?

LocoAware offers four data feeds, each at a different level of abstraction. Pick the one that matches the platform you're building:

| Feed | What you receive | Volume | Business logic |
|---|---|---|---|
| [Device Reports V2](./Device_Reports_V2_Integration_Guide.md) | Every device report — full state snapshot (sensors, location, observations, time series) | Highest | You implement it |
| [Device Events](./Device_Events_Integration_Guide.md) | Alerts and threshold crossings (temperature, impact, zone change, etc.) | Medium | Shared with LocoAware |
| **Shipments** (this guide) | Shipment-level events (position reports, exceptions, status changes, notes) | Lowest | LocoAware handles |
| [Assets](./Assets_Integration_Guide.md) | Asset-level events | Low | LocoAware handles |

**Choose Device Reports V2 if** you already run an advanced monitoring platform and want the raw device data to feed into your own rules engine. You'll get every report from every device and implement your own thresholds, dwell detection, and alerting.

**Choose Device Events if** you're building a custom platform on top of the SystemLoco IoT stack and want LocoAware to handle per-device threshold detection. You'll only hear about noteworthy events — a temperature threshold crossed, an impact detected, a zone change.

**Choose Shipments or Assets if** you're integrating an ERP, TMS, or other existing system, pushing data into a warehouse for Power BI / Tableau, or building a new platform scoped to shipments or assets rather than devices. Per-shipment business rules — "left origin", "entered destination", "off route", "missed scheduled completion" — are handled inside LocoAware. Far less data, far less business logic on your side, but you're tied to LocoAware's shipment/asset model.

Concretely: with Shipments you receive a webhook shaped like a shipment and only hear about exceptions and completion. With Device Reports V2 you hear about every beat from every device and decide for yourself what matters.

## Contents

1. [Overview](#1-overview)
2. [Setting Up a Data Feed](#2-setting-up-a-data-feed)
3. [Delivery Methods](#3-delivery-methods)
4. [Security](#4-security)
5. [Retry and Error Handling](#5-retry-and-error-handling)
6. [Message Format](#6-message-format)
7. [Event Types](#7-event-types)
8. [Type-Specific Payloads](#8-type-specific-payloads)
9. [Full Example](#9-full-example)
10. [Null Handling](#10-null-handling)
11. [Receiving Webhook Events](#11-receiving-webhook-events)

---

## 1. Overview

The Shipments feed delivers messages scoped to a shipment rather than a device. LocoAware handles all the per-shipment business rules internally — route adherence, scheduled departures, dwell thresholds, entity tracking — and emits events only at the points that matter to a transport operation: position reports, exceptions, status transitions, notes, and shipment lifecycle.

Each company can configure multiple data feeds, each with its own destination. Shipment feeds deliver messages for all shipments owned by the company (filtering is at the company level).

> **Creating shipments in LocoAware.** This feed is outbound — it emits events for shipments that already exist in LocoAware. SystemLoco is able to ingest shipment data into LocoAware (so the platform has shipments to track and emit events against in the first place) — speak to your SystemLoco representative about this.

---

## 2. Setting Up a Data Feed

Data feeds are created and managed through the **Integrations** application. When configuring a feed for shipment events, choose:

- **Source type**: `Shipment`
- **Destination**: HTTP webhook, AWS S3 bucket, or Azure Blob container

Once active, every shipment event for your company is delivered to the destination.

---

## 3. Delivery Methods

| Destination | Format |
|---|---|
| **HTTP Webhook** | POST with JSON body. Optional HMAC signing header. |
| **AWS S3** | JSON file at `{shipmentId}/{timestamp}_{messageId}.json` |
| **Azure Blob** | JSON file at `{shipmentId}/{timestamp}_{messageId}.json` |

For cloud storage destinations, `shipmentId` is the shipment's ID, `timestamp` is the event's source time in epoch milliseconds, and `messageId` is the unique event ID.

---

## 4. Security

### HTTP Webhook Signing

HTTP webhook destinations support optional HMAC signing. When enabled, each request includes a configured header containing an HMAC signature of the request body, computed with a shared secret. Verify this signature in your handler before trusting the payload.

### Cloud Storage

S3 and Azure destinations authenticate using credentials you provide during feed setup. LocoAware writes only to the configured bucket/container and path.

---

## 5. Retry and Error Handling

- **Maximum delivery attempts**: 3
- **Retry delay**: 1 minute after the first failure, 3 minutes for subsequent attempts
- **Dead letter queue**: Failed messages are retained for 14 days
- **Unhealthy feeds**: If a feed repeatedly fails, it is marked unhealthy and messages are quarantined until the issue is resolved

To ensure reliable delivery, your endpoint should respond with a 2xx status as quickly as possible. Defer any heavy processing to background work.

---

## 6. Message Format

### Envelope

Every shipment event message has this top-level structure:

```json
{
  "id": "6612a3f4e4b0a1b2c3d4e5f6",
  "time": "2026-04-09T10:30:00.000Z",
  "source": "shipment",
  "category": "event",
  "type": "report",
  "shipment": { ... },
  "location": { ... },
  "payload": { ... }
}
```

### Top-Level Fields

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Unique event ID |
| `time` | ISO 8601 | No | When the event occurred |
| `source` | string | No | Always `"shipment"` |
| `category` | string | No | Always `"event"` |
| `type` | string | No | Event type — see [Event Types](#7-event-types) |
| `shipment` | object | No | Shipment metadata |
| `location` | object | Yes | Location at event time |
| `payload` | object | Yes | Type-specific data — see [Type-Specific Payloads](#8-type-specific-payloads) |

### `shipment` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Shipment ID |
| `name` | string | No | Shipment name/reference |
| `status` | string | No | `"draft"`, `"ready"`, `"inProgress"`, or `"complete"` |
| `lastReported` | ISO 8601 | Yes | Time of the most recent position report |

### `location` Object

The shipment location is a flat coordinate object — it does not include indoor/site detail.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `lat` | double | No | Latitude (WGS84) |
| `lon` | double | No | Longitude (WGS84) |
| `cep` | double | Yes | Accuracy radius in metres (1dp) |
| `address` | string | Yes | Reverse-geocoded address |

---

## 7. Event Types

The `type` field determines the shape of the `payload`. Types fall into two groups.

### Structural Types

Messages that carry data rather than an alert condition.

| `type` | Description | Payload shape |
|---|---|---|
| `report` | Device or asset reported position and sensors | `report` payload |
| `note` | User added a note to the shipment | `note` payload |
| `shipmentCreated` | Shipment was created | `shipmentData` payload |
| `shipmentUpdated` | Shipment was updated | `shipmentData` payload |

### Alert / Lifecycle Types

All of these use the same payload shape (see [Alert Payload](#alert-payload)) — only the `type` and the populated payload fields differ. "Critical" indicates a high-severity alert LocoAware flags as business-critical.

| `type` | Critical | Description |
|---|---|---|
| `leavesOrigin` | No | Shipment departed origin |
| `entersDestination` | No | Shipment arrived at destination |
| `leavesRoute` | Yes | Shipment deviated from planned route |
| `entersRoute` | No | Shipment returned to planned route |
| `shocked` | Yes | Impact/shock detected |
| `tilted` | Yes | Tilt detected |
| `batteryLow` | Yes | Device battery low |
| `temperature` | Yes | Temperature threshold exceeded |
| `temperatureNormal` | No | Temperature returned to normal |
| `lightInTransit` | Yes | Light detected during transit |
| `dwell` | Yes | Stationary for extended period |
| `missedReports` | Yes | Expected device reports not received |
| `timeSinceLastReport` | Yes | Extended time since last report |
| `missedScheduledDeparture` | No | Didn't leave origin on schedule |
| `missedScheduledCompletion` | No | Didn't complete on schedule |
| `entityLeftAtOrigin` | Yes | Asset/device left behind at origin |
| `entityLeftAtDestination` | Yes | Asset/device left behind at destination |
| `entityUndelivered` | Yes | Asset/device departed after delivery attempt |
| `isReady` | No | Status changed to `ready` |
| `isInProgress` | No | Status changed to `inProgress` |
| `isComplete` | No | Status changed to `complete` |

---

## 8. Type-Specific Payloads

### `report` Payload

A device or asset reported a position with sensor readings. Exactly one of `device` or `asset` is present, depending on what reported.

```json
{
  "type": "report",
  "payload": {
    "device": {
      "id": "660e1a2b3c4d5e6f",
      "displayId": "HG-00012345",
      "name": "Tracker #7",
      "model": { "name": "HGx", "family": "locoCard", "product": "LocoCard", "version": 4, "revision": 2 },
      "firmware": "2.4.1",
      "labels": ["uk-fleet"]
    },
    "sensors": {
      "batteryLevel": 85,
      "lightLevel": 120,
      "temperature": 4.5,
      "movement": "stationary"
    }
  }
}
```

| Field | Type | Nullable | Description |
|---|---|---|---|
| `device` | object | Yes | Reporting device (same shape as the `device` object in the Device Events feed) |
| `asset` | object | Yes | Reporting asset |
| `asset.id` | string | No | Asset ID |
| `asset.name` | string | Yes | Asset name |
| `asset.type.id` | string | No | Asset type ID |
| `asset.type.name` | string | Yes | Asset type name |
| `asset.imageUrl` | string | Yes | Asset image URL |
| `sensors.batteryLevel` | integer | Yes | Battery percentage |
| `sensors.lightLevel` | integer | Yes | Ambient light level |
| `sensors.temperature` | double | Yes | Temperature in °C (1dp) |
| `sensors.movement` | string | Yes | `"stationary"` or `"moving"` |

### Alert Payload

All alert/lifecycle `type` values share this payload shape. Only fields relevant to the specific alert type are populated — the rest are omitted.

```json
{
  "type": "shocked",
  "payload": {
    "message": "Impact of 5.2g detected",
    "g": 5.2
  }
}
```

| Field | Type | Nullable | Present for |
|---|---|---|---|
| `message` | string | No | All alert types |
| `batteryLevel` | integer | Yes | `batteryLow` |
| `lightLevel` | integer | Yes | `lightInTransit` |
| `temperature` | double | Yes | `temperature`, `temperatureNormal` |
| `g` | double | Yes | `shocked` |
| `angle` | double | Yes | `tilted` |
| `dwellMinutes` | integer | Yes | `dwell` |
| `timeSinceLastReportMinutes` | integer | Yes | `timeSinceLastReport` |
| `missedReports` | integer | Yes | `missedReports` |

Numeric fields are parsed from the alert message. If parsing fails, the field is omitted.

### `note` Payload

A user added a note to the shipment.

```json
{
  "type": "note",
  "payload": {
    "title": "Driver check-in",
    "message": "Driver confirmed temperature is acceptable",
    "createdBy": {
      "id": "user123",
      "name": "John Smith"
    },
    "eventId": "6612a3f4e4b0a1b2c3d4e5f6"
  }
}
```

| Field | Type | Nullable | Description |
|---|---|---|---|
| `title` | string | No | Note title |
| `message` | string | No | Note body |
| `createdBy.id` | string | No | User ID |
| `createdBy.name` | string | No | User display name |
| `createdBy.email` | string | Yes | User email |
| `eventId` | string | Yes | Associated event ID, if the note is linked to an event |

### `shipmentCreated` / `shipmentUpdated` Payload

Emitted when a shipment is created or updated. The payload contains the full shipment as a JSON-encoded string, identical in shape to the response from `GET /api/shipment/{id}`.

```json
{
  "type": "shipmentCreated",
  "payload": {
    "data": "{\"id\":\"...\",\"name\":\"...\",\"status\":\"draft\",\"origin\":{...},\"destination\":{...}, ...}"
  }
}
```

| Field | Type | Description |
|---|---|---|
| `data` | string | Full shipment detail as a JSON-encoded string. Parse this to access the complete shipment — assets, devices, logistics, restrictions, etc. |

---

## 9. Full Example

### Position Report

```json
{
  "id": "evt_rpt_001",
  "time": "2026-04-09T10:30:00.000Z",
  "source": "shipment",
  "category": "event",
  "type": "report",
  "shipment": {
    "id": "661a1b2c3d4e5f6a",
    "name": "London to Manchester #42",
    "status": "inProgress",
    "lastReported": "2026-04-09T10:30:00.000Z"
  },
  "location": {
    "lat": 52.4862,
    "lon": -1.8904,
    "cep": 8.0,
    "address": "Birmingham, West Midlands, UK"
  },
  "payload": {
    "device": {
      "id": "660e1a2b3c4d5e6f",
      "displayId": "HG-00012345",
      "name": "Fleet Tracker #7",
      "model": { "name": "HGx", "family": "locoCard", "product": "LocoCard", "version": 4, "revision": 2 },
      "firmware": "2.4.1",
      "labels": ["uk-fleet"]
    },
    "sensors": {
      "batteryLevel": 85,
      "temperature": 4.5,
      "movement": "moving"
    }
  }
}
```

### Temperature Alert

```json
{
  "id": "evt_alert_002",
  "time": "2026-04-09T11:15:00.000Z",
  "source": "shipment",
  "category": "event",
  "type": "temperature",
  "shipment": {
    "id": "661a1b2c3d4e5f6a",
    "name": "London to Manchester #42",
    "status": "inProgress",
    "lastReported": "2026-04-09T11:15:00.000Z"
  },
  "location": {
    "lat": 53.0027,
    "lon": -2.1794,
    "address": "Stoke-on-Trent, Staffordshire, UK"
  },
  "payload": {
    "message": "Temperature 9.2°C exceeds maximum 8.0°C",
    "temperature": 9.2
  }
}
```

### Shipment Created

```json
{
  "id": "evt_created_003",
  "time": "2026-04-09T08:00:00.000Z",
  "source": "shipment",
  "category": "event",
  "type": "shipmentCreated",
  "shipment": {
    "id": "661a1b2c3d4e5f6a",
    "name": "London to Manchester #42",
    "status": "draft",
    "lastReported": null
  },
  "location": null,
  "payload": {
    "data": "{\"id\":\"661a1b2c3d4e5f6a\",\"name\":\"London to Manchester #42\", ...}"
  }
}
```

---

## 10. Null Handling

The feed omits null fields from the JSON entirely rather than sending them as `null`. Your integration should treat missing fields as absent/not-applicable, not expect explicit nulls.

---

## 11. Receiving Webhook Events

Shipment events are low-volume — even busy operations typically see only a handful per minute. Inline processing is usually fine; you don't need the queue/worker split that's mandatory for [Device Reports V2](./Device_Reports_V2_Integration_Guide.md) and [Device Events](./Device_Events_Integration_Guide.md). If you prefer architectural uniformity, the enqueue pattern works here too.

Working examples in Node, Java, C#, PHP, and Ruby live in [`examples/`](./examples/). The snippets below are abbreviated.

### The two associations

1. **Append to a shipment.** The event's `shipment.id` *is* the shipment — no lookup gymnastics required.
2. **Update by device ID** — for `report` events that include a `device`, update the latest state for that device if you're tracking it.

### Java (Spring)

```java
@PostMapping("/webhooks/shipments")
public ResponseEntity<Void> onShipmentEvent(@RequestBody ShipmentEvent event) {
    shipmentRepository.appendEvent(event.getShipment().getId(), event);

    if ("report".equals(event.getType()) && event.getPayload().getDevice() != null) {
        String deviceId = event.getPayload().getDevice().getId();
        if (deviceRepository.existsById(deviceId)) {
            deviceRepository.updateLatestEvent(deviceId, event);
        }
    }

    return ResponseEntity.ok().build();
}
```

`ShipmentEvent` is a POJO matching the envelope described above. Annotate it with `@JsonIgnoreProperties(ignoreUnknown = true)` so new fields added in future don't break deserialisation.

### Node (Express)

```javascript
app.post('/webhooks/shipments', verifySignature, async (req, res) => {
    const event = req.body;

    await shipments.appendEvent(event.shipment.id, event);

    if (event.type === 'report') {
        const deviceId = event.payload?.device?.id;
        if (deviceId && await devices.exists(deviceId)) {
            await devices.updateLatestEvent(deviceId, event);
        }
    }

    res.sendStatus(200);
});
```

Ensure `express.json()` middleware is mounted so `req.body` is parsed as JSON.

### Implementation Notes

- Return a 2xx response as soon as you've persisted the event. LocoAware retries on any non-2xx response.
- The two lookups run independently: a single event can both append to a shipment and update a device record.
- For `shipmentCreated` / `shipmentUpdated`, parse `payload.data` (a JSON string) to get the full shipment object.
- Treat missing fields as absent (see [Null Handling](#10-null-handling)) — alert payloads only include fields relevant to the alert type.
- If your webhook is HMAC-signed, verify the signature header before processing the body.
