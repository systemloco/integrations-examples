# LocoAware Device Reports V2 Integration Guide

This guide is for customers integrating their systems with LocoAware device data via the **Integrations** application. It describes the **Device Reports V2** data feed — the raw, point-in-time snapshot of device state — including the payload structure, every field you'll receive, and how to receive reports in your own service.

## Which data feed should I choose?

LocoAware offers four data feeds, each at a different level of abstraction. Pick the one that matches the platform you're building:

| Feed | What you receive | Volume | Business logic |
|---|---|---|---|
| **Device Reports V2** (this guide) | Every device report — full state snapshot (sensors, location, observations, time series) | Highest | You implement it |
| [Device Events](./Device_Events_Integration_Guide.md) | Alerts and threshold crossings (temperature, impact, zone change, etc.) | Medium | Shared with LocoAware |
| [Shipments](./Shipments_Integration_Guide.md) | Shipment-level events (position reports, exceptions, status changes, notes) | Lowest | LocoAware handles |
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
7. [Report Reasons](#7-report-reasons)
8. [Full Example](#8-full-example)
9. [Primary and Secondary Reporting](#9-primary-and-secondary-reporting)
10. [Null Handling](#10-null-handling)
11. [Receiving Webhook Events](#11-receiving-webhook-events)

---

## 1. Overview

LocoAware tracking devices periodically report their state. Each report is a point-in-time snapshot containing the device's identity, location, sensor readings, accelerometer events, observed networks, and related devices (observers, paired secondaries, third-party beacons). These reports can be streamed to your own systems via a **data feed**.

Each company can configure multiple data feeds, each with its own destination and filters, so you can route different subsets of reports to different systems.

---

## 2. Setting Up a Data Feed

Data feeds are created and managed through the **Integrations** application. When configuring a feed for device reports, choose:

- **Source type**: `DeviceReport`
- **Payload format**: `v2`
- **Destination**: HTTP webhook, AWS S3 bucket, or Azure Blob container
- **Filters**: which devices this feed delivers reports for (e.g. by device ID, model, or label)

Once active, every report matching the feed's filters is delivered to the destination.

---

## 3. Delivery Methods

| Destination | Format |
|---|---|
| **HTTP Webhook** | POST with JSON body. Optional HMAC signing header. |
| **AWS S3** | JSON file at `{deviceId}/{timestamp}_{messageId}.json` |
| **Azure Blob** | JSON file at `{deviceId}/{timestamp}_{messageId}.json` |

For cloud storage destinations, `deviceId` is the reporting device's ID, `timestamp` is the report's source time in epoch milliseconds, and `messageId` is the unique report ID.

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

Every device report message has this top-level structure:

```json
{
  "id": "6612a3f4e4b0a1b2c3d4e5f6",
  "time": "2026-04-09T10:30:00.000Z",
  "rxTime": "2026-04-09T10:30:02.150Z",
  "device": { ... },
  "owner": { ... },
  "reportReasons": ["moving", "temperature"],
  "reportedBy": { ... },
  "observedBy": [ ... ],
  "location": { ... },
  "sensors": { ... },
  "sensorEvents": [ ... ],
  "networkObservations": { ... },
  "secondaryObservations": [ ... ],
  "thirdPartyObservations": [ ... ],
  "timeSeries": { ... }
}
```

Reports are **instantaneous snapshots** — they do not have start or end times. `time` is when the device state was observed; `rxTime` is when the backend received it.

### Top-Level Fields

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Unique report ID |
| `time` | ISO 8601 | No | When the device state was observed |
| `rxTime` | ISO 8601 | Yes | When the report was received by the backend |
| `device` | object | No | The reporting device |
| `owner` | object | No | Company that owns the device |
| `reportReasons` | string[] | Yes | Why the device generated this report — see [Report Reasons](#7-report-reasons) |
| `reportedBy` | object | Yes | Who reported on behalf of this device (relay/observer/app) |
| `observedBy` | array | Yes | Other devices that observed this device over BLE |
| `location` | object | Yes | Device location at report time |
| `sensors` | object | Yes | Current sensor readings |
| `sensorEvents` | array | Yes | Transient accelerometer events (impact, drop, tip) |
| `networkObservations` | object | Yes | Wi-Fi / cellular networks seen by the device |
| `secondaryObservations` | array | Yes | Paired or secondary devices observed by this device |
| `thirdPartyObservations` | array | Yes | Third-party beacons observed by this device |
| `timeSeries` | object | Yes | Historical sensor readings included with the report |

### `device` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Device ID |
| `displayId` | string | No | User-friendly device identifier (serial, MAC, etc.) |
| `name` | string | Yes | User-assigned device name |
| `model.name` | string | No | Model identifier |
| `model.family` | string | No | Device family (e.g. `locoCard`, `reelable`, `chorus`) |
| `model.product` | string | No | Product line name |
| `model.version` | integer | No | Major version |
| `model.revision` | integer | No | Revision number |
| `model.variant` | string | Yes | Hardware variant (e.g. `pro`, `lite`) |
| `model.thirdParty` | boolean | Yes | True if this is a non-LocoAware beacon/sensor |
| `firmware` | string | Yes | Current firmware version |
| `labels` | string[] | No | User-defined labels/tags (may be empty) |

### `owner` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Company ID |
| `name` | string | No | Company name |

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
| `site` | object | Yes | Indoor/site-based location |
| `site.id` | string | No | Site ID |
| `site.name` | string | No | Site name |
| `site.level` | integer | No | Floor/level number |
| `site.x` | double | No | X-coordinate in metres (1dp) |
| `site.y` | double | No | Y-coordinate in metres (1dp) |
| `site.z` | double | Yes | Z-coordinate/height in metres (1dp) |
| `site.address` | string | Yes | Street address of the site |
| `site.cep` | double | Yes | Site location accuracy in metres |
| `site.rooms` | array | Yes | Rooms at this location, with `id` and `name` |
| `zones` | array | Yes | Zones containing this location, with `id` and `name` |

### `sensors` Object

| Field | Type | Nullable | Description |
|---|---|---|---|
| `time` | ISO 8601 | Yes | When the sensor readings were taken |
| `battery.level` | integer | Yes | Battery percentage (0–100) |
| `battery.state` | string | Yes | `good`, `warning`, `critical`, or `charging` |
| `battery.voltage` | double | Yes | Battery voltage |
| `battery.charging` | boolean | Yes | True if actively charging |
| `lightLevel` | integer | Yes | Light level in lux |
| `orientation.x` | double | No | Orientation X component |
| `orientation.y` | double | No | Orientation Y component |
| `orientation.z` | double | No | Orientation Z component |
| `vibration.x` | double | No | Vibration X component |
| `vibration.y` | double | No | Vibration Y component |
| `vibration.z` | double | No | Vibration Z component |
| `movement` | string | Yes | `moving` or `stationary` |
| `temperature` | double | Yes | Temperature in °C (1dp) |
| `extremeTemperature` | double | Yes | Extreme temperature reading in °C (1dp) |
| `atmosphericPressure` | double | Yes | Atmospheric pressure in hPa (1dp) |
| `humidity` | double | Yes | Relative humidity as % (1dp) |
| `securitySwitch.state` | string | No | `open` or `closed` |
| `securitySwitch.activations` | integer | No | Total switch activations |
| `securitySwitch.lastOpened` | ISO 8601 | Yes | Last time the switch was opened |
| `securitySwitch.lastClosed` | ISO 8601 | Yes | Last time the switch was closed |
| `externalPower` | boolean | Yes | True if external power is connected |
| `sterilisation.cycleCount` | integer | No | Number of sterilisation cycles completed |
| `sterilisation.cycleCountReadAt` | ISO 8601 | Yes | When the cycle count was last read |
| `sterilisation.uptimeInSeconds` | long | Yes | Uptime in seconds since last reset |
| `sterilisation.uptimeReadAt` | ISO 8601 | Yes | When uptime was last read |
| `cellSignal` | integer | Yes | Cellular signal strength in dBm (negative integer) |

### `sensorEvents` Array

Transient accelerometer events detected during the reporting window. Each element has a `type` discriminator:

```json
"sensorEvents": [
  { "type": "impact", "g": 5.23 },
  { "type": "drop",   "g": 3.10 },
  { "type": "tip",    "angle": 47.5 }
]
```

| `type` | Extra fields | Description |
|---|---|---|
| `impact` | `g` (double) | Shock/impact G-force |
| `drop` | `g` (double) | Drop G-force |
| `tip` | `angle` (double) | Tilt angle in degrees |

### `reportedBy` Object

Information about who actually transmitted this report — often the device itself, but may be a relay device, a paired primary, or a user's mobile app.

| Field | Type | Nullable | Description |
|---|---|---|---|
| `device` | object | Yes | Relay/primary device (same shape as top-level `device`) |
| `report` | object | Yes | Reference to the originating report (`id`, `time`) |
| `app` | object | Yes | Mobile app that scanned the device |
| `rssi` | integer | Yes | Signal strength between reporter and device, in dBm |

### `observedBy` Array

Other devices that observed this device over BLE. Each element:

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Observer device ID |
| `displayId` | string | No | Observer display ID |
| `name` | string | Yes | Observer name |
| `model` | object | No | Observer model (same shape as `device.model`) |
| `reportId` | string | Yes | ID of the observer's report |
| `reportTime` | ISO 8601 | Yes | When the observer generated its report |
| `rssi` | integer | Yes | Observed signal strength in dBm |

### `networkObservations` Object

Radio networks visible to the device at report time.

| Field | Type | Description |
|---|---|---|
| `wifi[]` | array | Wi-Fi access points: `mac`, `ssid`, `rssi` |
| `cells[]` | array | Cellular cells: `mcc`, `mnc`, `cell`, `lac`, `rssi` |
| `lteNeighbourCells[]` | array | LTE neighbours: `pci`, `earfcn`, `rsrq`, `rsrp` |

### `secondaryObservations` Array

Paired or secondary LocoAware devices observed by this device. Each element:

| Field | Type | Nullable | Description |
|---|---|---|---|
| `id` | string | No | Secondary device ID |
| `displayId` | string | No | Secondary display ID |
| `name` | string | Yes | Secondary name |
| `model` | object | No | Secondary model (same shape as `device.model`) |
| `reportType` | string | No | `event` (reportable event) or `presence` (passive sighting) |
| `reportEventId` | string | Yes | Event ID, if `reportType` is `event` |
| `reportId` | string | Yes | Report ID from the secondary |
| `rssi` | integer | Yes | Signal strength in dBm |
| `isPaired` | boolean | Yes | Whether the device is paired (null if pairing not applicable) |

### `thirdPartyObservations` Array

Third-party beacons observed by this device. Each element has a `type` discriminator: `reelable`, `chorus`, or `cableSeal`. All variants include `id` (MAC address) and `displayId`.

```json
"thirdPartyObservations": [
  {
    "type": "reelable",
    "id": "AA:BB:CC:DD:EE:FF",
    "displayId": "RL-12345",
    "temperature": 4.2
  },
  {
    "type": "chorus",
    "id": "11:22:33:44:55:66",
    "displayId": "CH-98765",
    "temperature": 22.1,
    "sequenceNumber": 42,
    "batteryLevel": 87.5,
    "rssi": -58,
    "tamper": false
  },
  {
    "type": "cableSeal",
    "id": "77:88:99:AA:BB:CC",
    "displayId": "CS-00011",
    "state": "closed",
    "open": false,
    "highTemperature": false,
    "lowBattery": false,
    "temperature": 18,
    "batteryLevel": 95,
    "counter": 3,
    "rssi": -62
  }
]
```

### `timeSeries` Object

Historical sensor readings gathered by the device and reported in a single batch. Each series is an array of `{time, value}` entries (`value.x/y/z` for vector series). Present series:

| Field | Value type |
|---|---|
| `temperature` | double (1dp) |
| `extremeTemperature` | double (1dp) |
| `humidity` | double (1dp) |
| `atmosphericPressure` | double (1dp) |
| `batteryVoltage` | double |
| `lightLevel` | integer |
| `orientation` | vector `{x, y, z}` |
| `vibration` | vector `{x, y, z}` |

---

## 7. Report Reasons

The `reportReasons` array lists why the device generated this report. A single report may have multiple reasons. Possible values:

| Reason | Description |
|---|---|
| `report` | Scheduled periodic report |
| `moving` | Movement detected |
| `temperature` | Temperature threshold crossed |
| `lightLevel` | Light level threshold crossed |
| `humidity` | Humidity threshold crossed |
| `atmosphericPressure` | Pressure threshold crossed |
| `impact` | Impact detected |
| `drop` | Drop detected |
| `tip` | Tilt detected |
| `securitySwitchOpen` | Security switch opened |
| `securitySwitchClosed` | Security switch closed |
| `observationStarted` | Started being observed by another device |
| `observationEnded` | Stopped being observed by another device |
| `observed` | Observed another device |
| `observerNew` | A new observer appeared |
| `observerMissing` | A previous observer is missing |
| `secondaryNew` | A new secondary/paired device appeared |
| `secondaryMissing` | A paired secondary is missing |
| `pairMissing` | The paired device is missing |
| `powerOn` | Device powered on |
| `powerOff` | Device powered off |
| `quickReport` | User-triggered quick report |
| `firmware` | Firmware updated |
| `settings` | Settings changed |
| `diagnostic` | Diagnostic report |
| `appScanUser` | Scanned via mobile app (foreground) |
| `appScanAutomatic` | Scanned via mobile app (background) |
| `deferred` | Report generated from historical data |
| `general` | Fallback reason |

---

## 8. Full Example

```json
{
  "id": "6612a3f4e4b0a1b2c3d4e5f6",
  "time": "2026-04-09T10:30:00.000Z",
  "rxTime": "2026-04-09T10:30:02.150Z",
  "device": {
    "id": "660e1a2b3c4d5e6f",
    "displayId": "HG-00012345",
    "name": "Cold Chain Tracker #7",
    "model": {
      "name": "HGD4",
      "family": "locoTrack",
      "product": "LocoTrack",
      "version": 4,
      "revision": 2
    },
    "firmware": "2.4.1",
    "labels": ["cold-chain", "uk-fleet"]
  },
  "owner": {
    "id": "660fa1b2c3d4e5f6a7b8c9d0",
    "name": "Acme Logistics"
  },
  "reportReasons": ["moving", "temperature"],
  "location": {
    "summary": "Near Manchester, UK",
    "type": "gps",
    "time": "2026-04-09T10:29:55.000Z",
    "global": {
      "lat": 53.4808,
      "lon": -2.2426,
      "cep": 12.5,
      "address": "Manchester, Greater Manchester, UK"
    },
    "zones": [
      { "id": "661a1b2c3d4e5f6a", "name": "Manchester Depot" }
    ]
  },
  "sensors": {
    "time": "2026-04-09T10:29:58.000Z",
    "battery": {
      "level": 78,
      "state": "good",
      "voltage": 3.8,
      "charging": false
    },
    "lightLevel": 400,
    "orientation": { "x": 0.02, "y": 0.05, "z": 1.00 },
    "vibration":   { "x": 0.04, "y": 0.06, "z": 0.03 },
    "movement": "moving",
    "temperature": 9.2,
    "humidity": 55.3,
    "atmosphericPressure": 1012.4,
    "externalPower": true,
    "cellSignal": -83
  },
  "sensorEvents": [
    { "type": "impact", "g": 4.12 }
  ],
  "networkObservations": {
    "wifi": [
      { "mac": "AA:BB:CC:DD:EE:FF", "ssid": "DepotWiFi", "rssi": -55 }
    ],
    "cells": [
      { "mcc": 234, "mnc": 15, "cell": 12345, "lac": 6789, "rssi": -95 }
    ],
    "lteNeighbourCells": [
      { "pci": 3, "earfcn": 6350, "rsrq": -5, "rsrp": -87 }
    ]
  },
  "timeSeries": {
    "temperature": [
      { "time": "2026-04-09T10:15:00.000Z", "value": 9.4 },
      { "time": "2026-04-09T10:29:58.000Z", "value": 9.2 }
    ],
    "atmosphericPressure": [
      { "time": "2026-04-09T10:15:00.000Z", "value": 1012.5 },
      { "time": "2026-04-09T10:29:58.000Z", "value": 1012.4 }
    ]
  }
}
```

---

## 9. Primary and Secondary Reporting

Some LocoAware devices act as **primaries** (a.k.a. masters) for one or more **secondaries** — typically BLE tags or third-party beacons paired to a cellular/Wi-Fi gateway. When a primary reports in, the feed delivers **two distinct messages** for each paired secondary it observed:

1. **The primary's report** — its own state, plus a `secondaryObservations[]` entry per tag it saw in this window. Each entry carries a `reportId` pointing at the secondary's matching report (below).
2. **Each secondary's own report** — a separate top-level message for the tag, with sensor data and `reportedBy.device` pointing back at the primary that relayed it.

The two messages are linked both ways: `secondaryObservations[].reportId` on the primary matches `id` on the secondary's report, and `reportedBy.device.id` on the secondary matches `device.id` on the primary's report.

Treat them as independent feed deliveries. They are not guaranteed to arrive in any particular order, and a worker that joins them should do so by ID rather than by arrival time.

### Primary device's report

A LocoTrack HGD4 hub reporting in, observing a fleet of paired temperature tags and a cable seal beacon. Trimmed for brevity — only the tag-relevant fields are filled in.

```json
{
  "id": "6612b1a0e4b0a1b2c3d4e5f6",
  "time": "2026-04-09T10:47:58.612Z",
  "rxTime": "2026-04-09T10:47:58.716Z",
  "device": {
    "id": "72308628874417738",
    "displayId": "HGD-00417738",
    "model": {
      "name": "HGD4",
      "family": "locoTrack",
      "product": "LocoTrack",
      "version": 4,
      "revision": 1
    },
    "labels": ["cold-chain", "us-west"]
  },
  "owner": {
    "id": "660fa1b2c3d4e5f6a7b8c9d0",
    "name": "Acme Logistics"
  },
  "reportReasons": ["report"],
  "location": {
    "type": "wifi",
    "time": "2026-04-09T10:47:58.612Z",
    "global": {
      "lat": 34.079725,
      "lon": -117.224535,
      "cep": 28.0
    }
  },
  "sensors": {
    "time": "2026-04-09T10:47:58.612Z",
    "battery": {
      "level": 100,
      "state": "good",
      "voltage": 6.226,
      "charging": false
    },
    "temperature": 25.1,
    "atmosphericPressure": 968.8,
    "lightLevel": 67,
    "orientation": { "x": 0.026, "y": 0.021, "z": -0.996 },
    "vibration":   { "x": 0.016, "y": 0.015, "z": 0.013 },
    "movement": "stationary",
    "externalPower": false
  },
  "secondaryObservations": [
    {
      "id": "72308628872452464",
      "displayId": "TAG-72452464",
      "model": { "name": "E1BL", "family": "locoCard", "product": "LocoCard", "version": 1, "revision": 0 },
      "reportType": "presence",
      "reportId": "6612b1a0e4b0a1b2c3d4e5f7",
      "rssi": -53,
      "isPaired": true
    },
    {
      "id": "72308628873734501",
      "displayId": "TAG-73734501",
      "model": { "name": "E1BL", "family": "locoCard", "product": "LocoCard", "version": 1, "revision": 0 },
      "reportType": "presence",
      "reportId": "6612b1a0e4b0a1b2c3d4e5f8",
      "rssi": -67,
      "isPaired": true
    }
  ],
  "thirdPartyObservations": [
    {
      "type": "cableSeal",
      "id": "A2:A2:A2:00:07:AB",
      "displayId": "A2A2A20007AB",
      "state": "closed",
      "open": false,
      "highTemperature": false,
      "lowBattery": false,
      "temperature": 21,
      "batteryLevel": 82,
      "counter": 41806,
      "rssi": -50
    }
  ],
  "networkObservations": {
    "wifi": [
      { "mac": "70:0f:6a:e9:f0:20", "ssid": "XPOSCWPA",      "rssi": -59 },
      { "mac": "70:0f:6a:e9:f0:22", "ssid": "XPOSC",         "rssi": -59 },
      { "mac": "b0:8b:cf:42:65:a4", "ssid": "GXO",           "rssi": -77 },
      { "mac": "70:0f:6a:e9:f0:23", "ssid": "XPOSC_Guest",   "rssi": -59 }
    ]
  }
}
```

### Secondary's (tag's) own report

A separate, top-level feed message for one of the tags above. Note `reportedBy.device.id` matching the primary, and `reportedBy.report.id` matching the `secondaryObservations[].reportId` from the primary's payload.

```json
{
  "id": "6612b1a0e4b0a1b2c3d4e5f7",
  "time": "2026-04-09T10:48:51.981Z",
  "rxTime": "2026-04-09T10:48:52.207Z",
  "device": {
    "id": "72308628872452464",
    "displayId": "TAG-72452464",
    "model": {
      "name": "E1BL",
      "family": "locoCard",
      "product": "LocoCard",
      "version": 1,
      "revision": 0
    },
    "labels": []
  },
  "owner": {
    "id": "660fa1b2c3d4e5f6a7b8c9d0",
    "name": "Acme Logistics"
  },
  "reportedBy": {
    "device": {
      "id": "72308628874417738",
      "displayId": "HGD-00417738",
      "model": { "name": "HGD4", "family": "locoTrack", "product": "LocoTrack", "version": 4, "revision": 1 }
    },
    "report": {
      "id": "6612b1a0e4b0a1b2c3d4e5f6",
      "time": "2026-04-09T10:47:58.612Z"
    },
    "rssi": -53
  },
  "location": {
    "type": "wifi",
    "time": "2026-04-09T10:48:51.981Z",
    "global": {
      "lat": 34.080073,
      "lon": -117.224648,
      "cep": 37.0
    }
  },
  "sensors": {
    "battery": {
      "level": 80,
      "state": "good",
      "charging": false
    },
    "externalPower": false
  },
  "timeSeries": {
    "temperature": [
      { "time": "2026-04-09T10:36:42.981Z", "value": 22.6 },
      { "time": "2026-04-09T10:51:42.981Z", "value": 22.6 },
      { "time": "2026-04-09T11:06:42.981Z", "value": 22.5 },
      { "time": "2026-04-09T11:21:42.981Z", "value": 22.5 },
      { "time": "2026-04-09T11:36:42.981Z", "value": 22.5 },
      { "time": "2026-04-09T11:51:42.981Z", "value": 22.4 }
    ]
  }
}
```

### Practical notes

- A primary that observes **N** paired tags produces **N + 1** feed messages per reporting window: one for itself with N entries in `secondaryObservations[]`, and one for each tag.
- Third-party beacons (cable seals, Reelable, Chorus) do **not** generate their own reports — they only appear under `thirdPartyObservations[]` on the observing device. Only LocoAware secondaries get standalone reports.
- If you store per-device latest state, the tag's standalone report is the authoritative source for the tag's sensors and time series. `secondaryObservations[]` on the primary is a presence/RSSI summary, not a sensor snapshot.
- Tags often have no GPS or Wi-Fi of their own; `location` on a tag's report typically reflects the primary's resolved position at the time of relay.

---

## 10. Null Handling

The feed omits null fields from the JSON entirely rather than sending them as `null`. Your integration should treat missing fields as absent/not-applicable, not expect explicit nulls. For example, a device without sterilisation tracking will have no `sensors.sterilisation` key at all.

---

## 11. Receiving Webhook Events

> **This is the highest-volume feed.** A single company with a few thousand active devices can produce **hundreds of reports per second**. Your webhook handler must do the absolute minimum on the request thread: verify the signature, push the payload onto a queue or `inbox` table, and respond `202 Accepted`. All real work — shipment lookup, threshold checks, database writes, downstream calls — belongs in a separate worker process you can scale independently. Inline processing **will** time out under fleet-scale load, and LocoAware retries non-2xx responses, multiplying the load further.

Working examples in five languages live in [`examples/`](./examples/) — Node, Java, C#, PHP, Ruby. The snippets below are abbreviated; the examples include HMAC verification, the queue/worker split, and the full lookup helper.

### The two associations

When the worker drains a report off the queue, it usually wants to do two independent things:

1. **Append the report to a shipment.** Look up the shipment by `device.name` (the conventional carrier for a shipment ID or name) **or** by `device.id` (find a shipment that currently has this device assigned). Either may match — try both.
2. **Update the per-device latest state.** If your system tracks this device, persist the report against `device.id`.

### Node (Express) — receiver

```javascript
app.post('/webhooks/device-reports', verifySignature, async (req, res) => {
    // Hot path: enqueue and ack. Do not touch the database here.
    await queue.publish('locoaware.device-reports', req.body);
    res.sendStatus(202);
});
```

### Node (Express) — worker

```javascript
async function handleDeviceReport(report) {
    const shipmentId = await shipments.findIdByDeviceNameOrId(
        report.device?.name,
        report.device?.id
    );
    if (shipmentId) {
        await shipments.appendReport(shipmentId, report);
    }

    const deviceId = report.device?.id;
    if (deviceId && await devices.exists(deviceId)) {
        await devices.updateLatestReport(deviceId, report);
    }
}
```

### Java (Spring) — receiver

```java
@PostMapping("/webhooks/device-reports")
public ResponseEntity<Void> onDeviceReport(@RequestBody JsonNode body) {
    queue.publish("locoaware.device-reports", body);
    return ResponseEntity.accepted().build();
}
```

`@RequestBody JsonNode` defers full deserialisation to the worker — the receiver doesn't need to understand the payload to enqueue it.

### Implementation Notes

- The two lookups run independently: a single report can both append to a shipment and update a device record.
- Cache the `findIdByDeviceNameOrId` lookup. Reports for an active device arrive in bursts — caching shipment-by-device for the duration of the shipment is the single biggest worker-side optimisation.
- Treat missing fields as absent (see [Null Handling](#10-null-handling)) — `device.name` may be unset for devices that haven't been assigned to a shipment.
- If your webhook is HMAC-signed, verify the signature **before** enqueueing.
- LocoAware retries on any non-2xx response. Return 2xx (typically 202) as soon as the message is durably enqueued.
