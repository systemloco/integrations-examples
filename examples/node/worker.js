// Worker — drains the queue populated by server.js.
//
// In production this is a separate long-running process (or a pool of them),
// subscribed to your real broker. It's deliberately separated from the web
// tier so ingest can't be stalled by slow database writes.

const { subscribe } = require('./lib/queue');
const { shipments, devices, assets } = require('./lib/repos');

// ----------------------------------------------------------------------------
// Shipment lookup, with a fallback to the relay/primary device.
//
// A secondary's (BLE tag's) report carries the primary that relayed it in
// `reportedBy.device`. Tags typically don't have a shipment binding of their
// own — falling back to the primary attaches the tag's data to the same
// shipment as the gateway it lives on.
// ----------------------------------------------------------------------------
async function findShipmentForReport(report) {
    const device = report.device || {};
    let shipmentId = await shipments.findIdByDeviceNameOrId(device.name, device.id);
    if (shipmentId) return shipmentId;

    const relay = report.reportedBy && report.reportedBy.device;
    if (relay && (relay.name || relay.id)) {
        shipmentId = await shipments.findIdByDeviceNameOrId(relay.name, relay.id);
    }
    return shipmentId;
}

// ----------------------------------------------------------------------------
// Device report handler.
// ----------------------------------------------------------------------------
async function handleDeviceReport(report) {
    const device = report.device || {};

    // 1. Shipment association — primary lookup, then primary's relay if any.
    const shipmentId = await findShipmentForReport(report);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, report);
    }

    // 2. Per-device latest state for the reporting device.
    if (device.id && await devices.exists(device.id)) {
        await devices.updateLatest(device.id, report);
    }

    // 3. Touch each observed secondary (paired tag). The tag's authoritative
    //    sensor data arrives in its own separate report — this is a presence
    //    touch so dashboards can show "last seen by primary" without waiting.
    for (const observation of report.secondaryObservations || []) {
        if (observation.id && await devices.exists(observation.id)) {
            await devices.updateLatest(observation.id, { observedBy: device.id, ...observation });
        }
    }
}

// ----------------------------------------------------------------------------
// Device event handler.
// ----------------------------------------------------------------------------
async function handleDeviceEvent(event) {
    const device = event.device || {};

    const shipmentId = await findShipmentForReport(event);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, event);
    }

    if (device.id && await devices.exists(device.id)) {
        await devices.updateLatest(device.id, event);
    }
}

subscribe('locoaware.device-reports', handleDeviceReport);
subscribe('locoaware.device-events', handleDeviceEvent);

console.log('[worker] subscribed to locoaware.device-reports and locoaware.device-events');
