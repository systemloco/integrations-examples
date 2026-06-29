// Worker — drains the queue populated by server.js.
//
// In production this is a separate long-running process (or a pool of them)
// subscribed to your real broker (SQS, Pub/Sub, Service Bus, Kafka, RabbitMQ,
// Redis Streams, ...). It's deliberately separated from the web tier so
// ingest can't be stalled by slow database writes.
//
// The queue stand-in in ./lib/queue.js is for illustration only — wire your
// real broker into that module.

const { subscribe } = require('./lib/queue');
const { parseDeviceReport, projectLatestState } = require('./lib/parse_report');
const { deviceReports, devices, shipments } = require('./lib/repos');

// ────────────────────────────────────────────────────────────────────────────
// Shipment lookup, with a fallback to the relay/primary device.
//
// A secondary's (BLE LocoTag) report carries the primary that relayed it in
// `reportedBy.device` / `relayedBy.device` (parsed shape). Tags typically
// don't have a shipment binding of their own — falling back to the primary
// attaches the tag's data to the same shipment as the gateway it lives on.
// ────────────────────────────────────────────────────────────────────────────
async function findShipmentForReport(doc) {
    const device = doc.device || {};
    let shipmentId = await shipments.findIdByDeviceNameOrId(device.name, device.id);
    if (shipmentId) return shipmentId;

    const relay = doc.relayedBy && doc.relayedBy.device;
    if (relay && (relay.name || relay.id)) {
        shipmentId = await shipments.findIdByDeviceNameOrId(relay.name, relay.id);
    }
    return shipmentId;
}

// ────────────────────────────────────────────────────────────────────────────
// Device report handler.
//
// Three writes per report:
//   1. Append the parsed report to its immutable `device_reports` history.
//   2. Append it to a shipment (if one binds this device or its primary).
//   3. Upsert the reporting device's "latest state" in `devices`.
//   4. For primaries only: presence-touch each paired secondary so dashboards
//      can show "last seen by primary at <time>" without waiting for the tag's
//      own report. Sensor data is NOT clobbered here — the tag's standalone
//      report is authoritative for its sensors.
// ────────────────────────────────────────────────────────────────────────────
async function handleDeviceReport(report) {
    const doc = parseDeviceReport(report);

    // 1. Immutable history.
    await deviceReports.insert(doc);

    // 2. Shipment association — primary lookup, then relay fallback for tags.
    const shipmentId = await findShipmentForReport(doc);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, doc);
    }

    // 3. Per-device latest state for the reporting device.
    const deviceId = doc.device && doc.device.id;
    if (deviceId && await devices.exists(deviceId)) {
        await devices.upsertLatest(deviceId, projectLatestState(doc));
    }

    // 4. Primary-side presence touches for paired secondaries.
    //    role === 'primary' means this is a LocoTrack hub reporting in with
    //    a list of BLE LocoTags it observed. We only update `lastSeenBy` on
    //    each tag — never the tag's own sensor fields.
    if (doc.role === 'primary' && Array.isArray(doc.secondaries)) {
        for (const obs of doc.secondaries) {
            if (!obs.id) continue;
            if (!(await devices.exists(obs.id))) continue;
            await devices.touchObservation(obs.id, {
                primaryDeviceId: deviceId,
                rssi: obs.rssi,         // nullable
                reportType: obs.reportType,
                time: doc.time,
            });
        }
    }
}

// ────────────────────────────────────────────────────────────────────────────
// Device event handler — same lookup, simpler write.
// ────────────────────────────────────────────────────────────────────────────
async function handleDeviceEvent(event) {
    // Device events use a similar envelope; reuse the parser to get a
    // consistently-shaped document for the doc store.
    const doc = parseDeviceReport(event);

    const shipmentId = await findShipmentForReport(doc);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, doc);
    }

    const deviceId = doc.device && doc.device.id;
    if (deviceId && await devices.exists(deviceId)) {
        await devices.upsertLatest(deviceId, projectLatestState(doc));
    }
}

if (require.main === module) {
    subscribe('locoaware.device-reports', handleDeviceReport);
    subscribe('locoaware.device-events', handleDeviceEvent);
    console.log('[worker] subscribed to locoaware.device-reports and locoaware.device-events');
}

module.exports = { handleDeviceReport, handleDeviceEvent, findShipmentForReport };
