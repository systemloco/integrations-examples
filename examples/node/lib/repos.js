// In-memory stand-in for a document store. The shape mirrors what you'd push
// into MongoDB / DocumentDB / CosmosDB / Firestore / Couchbase. The point of
// the examples is to show the SHAPE of the documents — the actual driver is
// one swap away.
//
// Production replacements:
//   - MongoDB        mongodb (Node native driver)        collection.updateOne({_id}, {$set: doc}, {upsert: true})
//   - DocumentDB     mongodb-compatible — same call
//   - CosmosDB       @azure/cosmos                       container.items.upsert(doc)
//   - Firestore      @google-cloud/firestore             collection.doc(id).set(doc, {merge: true})
//   - DynamoDB       @aws-sdk/client-dynamodb            UpdateItem with UpsertExpression
//   - Couchbase      couchbase                           collection.upsert(id, doc)
//
// We deliberately keep schema/migrations out of scope here — pick a relational
// shape that fits your warehouse separately if you need it.

const log = (...args) => console.log('[repo]', ...args);

// One Map per logical "collection". Each Map is keyed by document _id.
const stores = {
    deviceReports: new Map(),   // _id (report id) -> parsed report document   (immutable history)
    devices: new Map(),         // _id (device id) -> latest-state document    (upserted)
    shipments: new Map(),       // _id (shipment id) -> { _id, events: [...] } (events appended)
    assets: new Map(),          // _id (asset id) -> latest-state document     (upserted)
};

// ────────────────────────────────────────────────────────────────────────────
// Device reports — immutable history. One document per delivered webhook.
// ────────────────────────────────────────────────────────────────────────────
const deviceReports = {
    async insert(doc) {
        if (!doc || !doc._id) return;
        // In production: await collection.insertOne(doc)
        // (Use the report's own id as _id so re-deliveries are idempotent.)
        stores.deviceReports.set(doc._id, doc);
        log(`insert device_report ${doc._id} (role=${doc.role || 'unknown'})`);
    },

    // Helpers for tests/inspection — not part of the production-flow API.
    _get(id) { return stores.deviceReports.get(id); },
    _all() { return Array.from(stores.deviceReports.values()); },
    _clear() { stores.deviceReports.clear(); },
};

// ────────────────────────────────────────────────────────────────────────────
// Devices — per-device latest state. Upserted on every report.
// ────────────────────────────────────────────────────────────────────────────
const devices = {
    async exists(deviceId) {
        if (!deviceId) return false;
        // Real implementations might pre-load known device IDs into a set, or
        // do `collection.findOne({_id}, {projection: {_id: 1}})`. For the
        // stand-in we accept any non-empty id so the worker is exercised.
        return true;
    },

    async upsertLatest(deviceId, patch) {
        if (!deviceId) return;
        const current = stores.devices.get(deviceId) || { _id: deviceId };
        const next = { ...current, ...patch, _id: deviceId };
        stores.devices.set(deviceId, next);
        // In production:
        //   await collection.updateOne(
        //       { _id: deviceId },
        //       { $set: patch },
        //       { upsert: true }
        //   )
        log(`upsert device ${deviceId}`);
    },

    // Presence-only touch: a secondary's primary saw it during a reporting
    // window. The tag's AUTHORITATIVE sensor data arrives in the tag's own
    // standalone report — do not overwrite sensor fields here.
    async touchObservation(secondaryId, { primaryDeviceId, rssi, time, reportType }) {
        if (!secondaryId) return;
        const current = stores.devices.get(secondaryId) || { _id: secondaryId };
        const lastSeenBy = {
            primaryDeviceId,
            // nullable — primary's reading of the secondary may omit any of these
            ...(rssi !== undefined && rssi !== null ? { rssi } : {}),
            ...(reportType ? { reportType } : {}),
            ...(time ? { time } : {}),
        };
        stores.devices.set(secondaryId, { ...current, _id: secondaryId, lastSeenBy });
        // In production:
        //   await collection.updateOne(
        //       { _id: secondaryId },
        //       { $set: { lastSeenBy } },
        //       { upsert: true }
        //   )
        log(`touch device ${secondaryId} seen by primary ${primaryDeviceId}`);
    },

    // Tests/inspection.
    _get(id) { return stores.devices.get(id); },
    _all() { return Array.from(stores.devices.values()); },
    _clear() { stores.devices.clear(); },
};

// ────────────────────────────────────────────────────────────────────────────
// Shipments — events appended. Lookup helpers are left as stubs (return null)
// because real lookups are deployment-specific (SQL with indexes, an Atlas
// search, a cached side-table, etc.). Tests can seed via `_seed(id, doc)`.
// ────────────────────────────────────────────────────────────────────────────
const shipments = {
    async appendEvent(shipmentId, event) {
        if (!shipmentId) return;
        const current = stores.shipments.get(shipmentId) || { _id: shipmentId, events: [] };
        current.events.push(event);
        stores.shipments.set(shipmentId, current);
        // In production:
        //   await collection.updateOne(
        //       { _id: shipmentId },
        //       { $push: { events: event } },
        //       { upsert: true }
        //   )
        log(`append event ${event && event._id ? event._id : (event && event.id) || '(message)'} → shipment ${shipmentId}`);
    },

    // Return the shipment id associated with EITHER the device's name (which
    // often carries a shipment id/name) OR the device's id (if the device is
    // assigned to an active shipment). This is the worker's hottest read —
    // cache aggressively in production.
    //
    // Real implementation (one indexed statement):
    //   SELECT id FROM shipments
    //   WHERE status IN ('ready', 'inProgress')
    //     AND (name = $1 OR id = $1 OR id IN (
    //       SELECT shipment_id FROM shipment_devices WHERE device_id = $2
    //     ))
    //   LIMIT 1;
    async findIdByDeviceNameOrId(deviceName, deviceId) {
        log(`lookup shipment by deviceName=${deviceName} OR deviceId=${deviceId}`);
        // Test seed: a shipment "binds" a device via _seed.
        for (const [id, doc] of stores.shipments) {
            if (deviceName && doc.boundDeviceNames?.includes(deviceName)) return id;
            if (deviceId && doc.boundDeviceIds?.includes(deviceId)) return id;
        }
        return null;
    },

    async findIdByAssetNameOrId(assetName, assetId) {
        log(`lookup shipment by assetName=${assetName} OR assetId=${assetId}`);
        for (const [id, doc] of stores.shipments) {
            if (assetName && doc.boundAssetNames?.includes(assetName)) return id;
            if (assetId && doc.boundAssetIds?.includes(assetId)) return id;
        }
        return null;
    },

    // Tests/inspection.
    _seed(id, doc) { stores.shipments.set(id, { _id: id, events: [], ...doc }); },
    _get(id) { return stores.shipments.get(id); },
    _all() { return Array.from(stores.shipments.values()); },
    _clear() { stores.shipments.clear(); },
};

// ────────────────────────────────────────────────────────────────────────────
// Assets — same shape as devices.
// ────────────────────────────────────────────────────────────────────────────
const assets = {
    async exists(assetId) { return !!assetId; },
    async updateLatest(assetId, event) {
        if (!assetId) return;
        const current = stores.assets.get(assetId) || { _id: assetId };
        stores.assets.set(assetId, { ...current, lastEvent: event, updatedAt: event && event.time });
        log(`upsert asset ${assetId}`);
    },
    _get(id) { return stores.assets.get(id); },
    _all() { return Array.from(stores.assets.values()); },
    _clear() { stores.assets.clear(); },
};

function _resetAll() {
    stores.deviceReports.clear();
    stores.devices.clear();
    stores.shipments.clear();
    stores.assets.clear();
}

module.exports = { deviceReports, devices, shipments, assets, _resetAll };
