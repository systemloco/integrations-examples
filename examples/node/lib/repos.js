// TODO: replace these stand-ins with your real persistence layer.
//
// The methods you need:
//
//   shipments.appendEvent(shipmentId, event)
//       Append the event to the shipment's event log.
//
//   shipments.findIdByDeviceNameOrId(deviceName, deviceId)
//       Return the ID of the shipment currently associated with EITHER
//       the device's name field (which often carries a shipment ID / name)
//       OR the device's id (which finds shipments the device is assigned to).
//       This is the single hottest read in the worker — cache it.
//
//   shipments.findIdByAssetNameOrId(assetName, assetId)
//       Same shape as above, but for asset-level events.
//
//   devices.exists(id) / devices.updateLatest(id, event)
//   assets.exists(id)  / assets.updateLatest(id, event)

const log = (...args) => console.log('[repo]', ...args);

const shipments = {
    async appendEvent(shipmentId, event) {
        log(`append event ${event.id || '(shipment message)'} → shipment ${shipmentId}`);
    },

    async findIdByDeviceNameOrId(deviceName, deviceId) {
        // Real implementation (one SQL statement with an index on both columns):
        //
        //   SELECT id FROM shipments
        //   WHERE status IN ('ready', 'inProgress')
        //     AND (name = $1 OR id = $1 OR id IN (
        //       SELECT shipment_id FROM shipment_devices WHERE device_id = $2
        //     ))
        //   LIMIT 1;
        //
        // Cache by (deviceName, deviceId) for the lifetime of the shipment —
        // a device emits many reports in rapid succession.
        log(`lookup shipment by deviceName=${deviceName} OR deviceId=${deviceId}`);
        return null;
    },

    async findIdByAssetNameOrId(assetName, assetId) {
        log(`lookup shipment by assetName=${assetName} OR assetId=${assetId}`);
        return null;
    },
};

const devices = {
    async exists(deviceId) { return !!deviceId; },
    async updateLatest(deviceId, event) {
        log(`update latest for device ${deviceId}`);
    },
};

const assets = {
    async exists(assetId) { return !!assetId; },
    async updateLatest(assetId, event) {
        log(`update latest for asset ${assetId}`);
    },
};

module.exports = { shipments, devices, assets };
