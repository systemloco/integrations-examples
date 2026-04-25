# LocoAware Assets Integration Guide

This guide is for customers integrating their systems with LocoAware asset data via the **Integrations** application. It describes the **Assets** data feed — events scoped to a tracked asset such as a pallet, container, or piece of equipment — including the payload structure, every event type, and how to receive events in your own service.

## Which data feed should I choose?

LocoAware offers four data feeds, each at a different level of abstraction. Pick the one that matches the platform you're building:

| Feed | What you receive | Volume | Business logic |
|---|---|---|---|
| [Device Reports V2](./Device_Reports_V2_Integration_Guide.md) | Every device report — full state snapshot (sensors, location, observations, time series) | Highest | You implement it |
| [Device Events](./Device_Events_Integration_Guide.md) | Alerts and threshold crossings (temperature, impact, zone change, etc.) | Medium | Shared with LocoAware |
| [Shipments](./Shipments_Integration_Guide.md) | Shipment-level events (position reports, exceptions, status changes, notes) | Lowest | LocoAware handles |
| **Assets** (this guide) | Asset-level events | Low | LocoAware handles |

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
11. [Event Timing](#11-event-timing)
12. [Receiving Webhook Events](#12-receiving-webhook-events)

---

## 1. Overview

The Assets feed delivers events scoped to a tracked asset rather than the device attached to it. If you care about what's happening to a pallet, container, or piece of equipment — and don't want to reason about which device is reporting on its behalf or when that changes — this is the feed to use.

Asset events carry the same shape as device events, but the envelope references the asset (`asset`) instead of a device. LocoAware resolves the underlying device, runs threshold and zone logic, and emits the event against the asset.

Each company can configure multiple data feeds, each with its own destination and filters.

---

## 2. Setting Up a Data Feed

Data feeds are created and managed through the **Integrations** application. When configuring a feed for asset events, choose:

- **Source type**: `Asset`
- **Destination**: HTTP webhook, AWS S3 bucket, or Azure Blob container
- **Filters**: which assets this feed delivers events for (e.g. by asset type)

Once active, every event matching the feed's filters is delivered to the destination.

---

## 3. Delivery Methods

| Destination | Format |
|---|---|
| **HTTP Webhook** | POST with JSON body. Optional HMAC signing header. |
| **AWS S3** | JSON file at `{assetId}/{timestamp}_{messageId}.json` |
| **Azure Blob** | JSON file at `{assetId}/{timestamp}_{messageId}.json` |

For cloud storage destinations, `assetId` is the asset's ID, `timestamp` is the event's source time in epoch milliseconds, and `messageId` is the unique event ID.

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

Every asset event message has this top-level structure:

```json
{
  "id": "6612a3f4e4b0a1b2c3d4e5f6",
  "owner": {
    "id": "660fa1b2c3d4e5f6a7b8c9d0",
    "name": "Acme Logistics"
  },
  "category": "event",
  "type": "temperature",
  "startTime": "2026-04-09T10:30:00.000Z",
  "latestTime": "2026-04-09T10:30:00.000Z",
  "endTime": null,
  "location": { ... },
  "payload": { ... },
  "asset": { ... }
}
```

### Top-Level Fields

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Unique event ID |
| `owner.id` | string | No | Company/organisation ID |
| `owner.name` | string | No | Company name |
| `category` | string | No | `"event"` or `"warning"` |
| `type` | string | No | Event type — see [Event Types](#7-event-types) |
| `startTime` | ISO 8601 | No | When the event started |
| `latestTime` | ISO 8601 | No | When the event was last updated |
| `endTime` | ISO 8601 | Yes | When the event ended. Null = ongoing. |
| `location` | object | Yes | Asset location at event time |
| `payload` | object | Yes | Type-specific data — see [Type-Specific Payloads](#8-type-specific-payloads) |
| `asset` | object | No | Asset the event pertains to |

### `asset` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Asset ID |
| `name` | string | Yes | Asset name |
| `type.id` | string | No | Asset type ID |
| `type.name` | string | Yes | Asset type name (e.g. `"Pallet"`, `"Reefer Container"`) |
| `type.imageUrl` | string | Yes | Asset type image URL |
| `imageUrl` | string | Yes | Asset image URL |

### `location` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `summary` | string | Yes | Human-readable location description |
| `type` | string | Yes | `gps`, `cell`, `wifi`, `indoor`, `uwb`, `fixed`, `app`, `unchanged`, or `unclassified` |
| `time` | ISO 8601 | Yes | When the location was determined |
| `global` | object | Yes | GPS/global coordinates |
| `global.lat` | double | No | Latitude (WGS84) |
| `global.lon` | double | No | Longitude (WGS84) |
| `global.cep` | double | Yes | Accuracy radius in metres (1dp) |
| `global.address` | string | Yes | Reverse-geocoded address |
| `site` | object | Yes | Indoor/site-based location (id, name, level, x, y, z, address, rooms[]) |
| `zones` | array | Yes | Zones containing this location, with `id` and `name` |

---

## 7. Event Types

Asset events are a subset of the device event types — only those events that make sense at the asset level are emitted.

| `type` value | Category | Description |
|---|---|---|
| `light` | event | Light level exceeded threshold |
| `location` | event | New global (GPS/cell/wifi) location fix |
| `siteLocation` | event | New indoor/site location fix |
| `onSite` | event | Asset detected at a site |
| `temperature` | event | Temperature exceeded threshold |
| `impact` | event | Shock/impact detected |
| `drop` | event | Drop detected |
| `tip` | event | Tilt angle exceeded threshold |
| `zoneChange` | event | Asset moved between zones |
| `tamper` | warning | Tamper detected |

Device-only events — `firmware`, `securitySwitch`, `charging`, `battery`, `dataDownload`, `coldChain`, `sterilisation`, `missing` — are not emitted on the Assets feed.

---

## 8. Type-Specific Payloads

The `payload` object varies by event type. Fields are only present when they have a value (see [Null Handling](#10-null-handling)).

### `temperature`

```json
{
  "payload": {
    "temperature": 12.3,
    "maxTemperature": 8.0,
    "minTemperature": 2.0
  }
}
```

| Field | Type | Description |
|---|---|---|
| `temperature` | double | Current temperature (1dp) |
| `maxTemperature` | double | Configured max threshold |
| `minTemperature` | double | Configured min threshold |

### `light`

```json
{
  "payload": {
    "lightLevel": 4500,
    "maxLightLevel": 32767,
    "minLightLevel": 0
  }
}
```

| Field | Type | Description |
|---|---|---|
| `lightLevel` | integer | Current light level reading |
| `maxLightLevel` | integer | Configured max threshold |
| `minLightLevel` | integer | Configured min threshold |

### `impact`

```json
{
  "payload": {
    "g": 5.23
  }
}
```

| Field | Type | Description |
|---|---|---|
| `g` | double | G-force value (2dp) |

### `drop`

```json
{
  "payload": {
    "g": 3.10
  }
}
```

| Field | Type | Description |
|---|---|---|
| `g` | double | G-force value (2dp) |

### `tip`

```json
{
  "payload": {
    "angle": 47.50
  }
}
```

| Field | Type | Description |
|---|---|---|
| `angle` | double | Tilt angle in degrees (2dp) |

### `zoneChange`

```json
{
  "payload": {
    "remainedInside": [
      { "id": "zone1", "name": "Warehouse A" }
    ],
    "movedInside": [
      { "id": "zone2", "name": "Loading Bay" }
    ],
    "movedOutside": [
      { "id": "zone3", "name": "Office Block" }
    ]
  }
}
```

| Field | Type | Description |
|---|---|---|
| `remainedInside` | array | Zones the asset was and still is in |
| `movedInside` | array | Zones the asset has entered |
| `movedOutside` | array | Zones the asset has left |
| Each zone: `id` | string | Zone ID |
| Each zone: `name` | string | Zone name |

### Events with no payload

These event types have **no `payload` object** — the event itself (with `location`, `startTime`/`endTime`) is the data:

- `location`
- `siteLocation`
- `onSite`
- `tamper`

---

## 9. Full Example

```json
{
  "id": "6612a3f4e4b0a1b2c3d4e5f6",
  "owner": {
    "id": "660fa1b2c3d4e5f6a7b8c9d0",
    "name": "Acme Logistics"
  },
  "category": "event",
  "type": "temperature",
  "startTime": "2026-04-09T10:30:00.000Z",
  "latestTime": "2026-04-09T10:30:00.000Z",
  "endTime": null,
  "location": {
    "summary": "Warehouse, Site 1",
    "type": "uwb",
    "time": "2026-04-09T10:29:55.000Z",
    "site": {
      "id": "661a1b2c3d4e5f6a",
      "name": "Manchester Depot",
      "level": 2,
      "x": 25.4,
      "y": 30.2,
      "address": "123 Industrial Rd, Manchester"
    },
    "zones": [
      { "id": "662b2c3d4e5f6a7b", "name": "Cold Store A" }
    ]
  },
  "asset": {
    "id": "663c3d4e5f6a7b8c",
    "name": "Reefer-042",
    "type": {
      "id": "664d4e5f6a7b8c9d",
      "name": "Reefer Container"
    },
    "imageUrl": "https://images.example.com/reefer-042.jpg"
  },
  "payload": {
    "temperature": 9.2,
    "maxTemperature": 8.0,
    "minTemperature": 2.0
  }
}
```

---

## 10. Null Handling

The feed omits null fields from the JSON entirely rather than sending them as `null`. Your integration should treat missing fields as absent/not-applicable, not expect explicit nulls.

---

## 11. Event Timing

- **Instantaneous events** (impact, drop, tip): `startTime == endTime`
- **Ongoing events** (zone presence): `endTime` is null while active, set when the event completes
- **Feed deduplication**: Events are only published when `latestTime == startTime` (new event) or `latestTime == endTime` (event completed). Updates in between are not published.

---

## 12. Receiving Webhook Events

Asset events are low-volume — usually a handful per minute. Inline processing is fine; you don't need the queue/worker split that's mandatory for [Device Reports V2](./Device_Reports_V2_Integration_Guide.md) and [Device Events](./Device_Events_Integration_Guide.md).

Working examples in Node, Java, C#, PHP, and Ruby live in [`examples/`](./examples/). The snippets below are abbreviated.

### The two associations

1. **Append to a shipment.** Look up by `asset.name` (the conventional carrier for a shipment reference) **or** by `asset.id` (find a shipment that currently has this asset assigned). Either may match — try both.
2. **Update by asset ID** — if your system already tracks this asset, update its latest state.

### Java (Spring)

```java
@PostMapping("/webhooks/assets")
public ResponseEntity<Void> onAssetEvent(@RequestBody AssetEvent event) {
    String shipmentId = shipmentRepository.findIdByAssetNameOrId(
        event.getAsset().getName(),
        event.getAsset().getId()
    );
    if (shipmentId != null) {
        shipmentRepository.appendEvent(shipmentId, event);
    }

    String assetId = event.getAsset().getId();
    if (assetRepository.existsById(assetId)) {
        assetRepository.updateLatestEvent(assetId, event);
    }

    return ResponseEntity.ok().build();
}
```

`AssetEvent` is a POJO matching the envelope described above. Annotate it with `@JsonIgnoreProperties(ignoreUnknown = true)` so new fields added in future don't break deserialisation.

### Node (Express)

```javascript
app.post('/webhooks/assets', verifySignature, async (req, res) => {
    const event = req.body;

    const shipmentId = await shipments.findIdByAssetNameOrId(
        event.asset?.name,
        event.asset?.id
    );
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, event);
    }

    const assetId = event.asset?.id;
    if (assetId && await assets.exists(assetId)) {
        await assets.updateLatestEvent(assetId, event);
    }

    res.sendStatus(200);
});
```

Ensure `express.json()` middleware is mounted so `req.body` is parsed as JSON.

### Implementation Notes

- Return a 2xx response as soon as you've persisted the event. LocoAware retries on any non-2xx response.
- The two lookups run independently: a single event can both append to a shipment and update an asset record.
- Treat missing fields as absent (see [Null Handling](#10-null-handling)) — `asset.name` may be unset for assets that haven't been named.
- If your webhook is HMAC-signed, verify the signature header before processing the body.
