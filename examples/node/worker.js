// Worker — drains the queue populated by server.js.
//
// In production this is a separate long-running process (or a pool of them),
// subscribed to your real broker. It's deliberately separated from the web
// tier so ingest can't be stalled by slow database writes.

const { subscribe } = require('./lib/queue');
const { shipments, devices, assets } = require('./lib/repos');

// ----------------------------------------------------------------------------
// Device report handler.
// ----------------------------------------------------------------------------
async function handleDeviceReport(report) {
    const device = report.device || {};

    // 1. Shipment association. Two lookups folded into one repo call:
    //    a) device.name may hold a shipment ID or shipment name
    //    b) device.id may be assigned to an active shipment
    const shipmentId = await shipments.findIdByDeviceNameOrId(device.name, device.id);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, report);
    }

    // 2. Per-device latest state.
    if (device.id && await devices.exists(device.id)) {
        await devices.updateLatest(device.id, report);
    }
}

// ----------------------------------------------------------------------------
// Device event handler.
// ----------------------------------------------------------------------------
async function handleDeviceEvent(event) {
    const device = event.device || {};

    const shipmentId = await shipments.findIdByDeviceNameOrId(device.name, device.id);
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
