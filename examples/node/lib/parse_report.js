// Parse a Device Reports V2 webhook payload into a document-store-shaped record.
//
// The output mirrors what you'd insert into MongoDB / DocumentDB / Couchbase /
// Firestore — `_id` keyed to the report ID, with each documented subsection
// extracted into a named field. Tables and schema migrations are explicitly
// out of scope; pick the relational shape that fits your warehouse separately.
//
// ─── Null handling ───────────────────────────────────────────────────────────
// The feed OMITS null/absent fields entirely rather than sending them as
// `null` (see Device_Reports_V2_Integration_Guide.md §10). This parser keeps
// that convention: every optional field is only set on the output document if
// the source field is present and non-null. Optional outputs are flagged
// "// nullable —" inline below.
//
// ─── Primary / Secondary reporting ───────────────────────────────────────────
// LocoTrack hubs (primaries, a.k.a. "masters") relay BLE LocoTag (secondary,
// a.k.a. "slave") state. The same tag emits TWO feed messages per reporting
// window:
//
//   1. The primary's own report, listing each tag it saw in `secondaryObservations[]`.
//   2. Each tag's own standalone report, with `reportedBy.device` pointing back
//      at the primary that relayed it.
//
// This parser tags each document with a `role`:
//   - "primary"    — this report came from a hub that observed secondaries
//   - "secondary"  — this report was relayed by a primary (reportedBy.device set)
//   - "standalone" — a self-contained device reporting itself
//
// The two messages link back together with:
//   primaryDoc.secondaries[i].reportId === secondaryDoc._id
//   secondaryDoc.relayedBy.reportId    === primaryDoc._id
//
// Treat them as independent feed deliveries — they are not guaranteed to
// arrive in order, so join by ID rather than by arrival time.

const isPresent = (v) => v !== undefined && v !== null;

// Copy a list of keys from `src` to a new object, skipping keys whose source
// value is null/undefined. Keeps the output free of explicit `null`s so it
// reads the same way the feed wire format does.
function pickPresent(src, keys) {
    if (!src) return undefined;
    const out = {};
    for (const k of keys) {
        if (isPresent(src[k])) out[k] = src[k];
    }
    return Object.keys(out).length ? out : undefined;
}

function parseModel(model) {
    return pickPresent(model, ['name', 'family', 'product', 'version', 'revision', 'variant', 'thirdParty']);
}

function parseDeviceIdentity(device) {
    if (!device) return undefined;
    const out = {
        id: device.id,
        displayId: device.displayId,
        model: parseModel(device.model),
        labels: device.labels || [],
    };
    if (isPresent(device.name)) out.name = device.name;          // nullable — user-assigned
    if (isPresent(device.firmware)) out.firmware = device.firmware; // nullable
    return out;
}

function parseLocation(location) {
    if (!location) return undefined;
    const out = {};
    if (isPresent(location.type)) out.type = location.type;       // nullable
    if (isPresent(location.time)) out.time = location.time;       // nullable
    if (isPresent(location.summary)) out.summary = location.summary; // nullable

    if (location.global) {
        // lat/lon are non-null when `global` is present; cep/address are nullable.
        out.global = pickPresent(location.global, ['lat', 'lon', 'cep', 'address']);
    }
    if (location.site) {
        out.site = pickPresent(location.site, ['id', 'name', 'level', 'x', 'y', 'z', 'address', 'cep', 'rooms']);
    }
    if (Array.isArray(location.zones) && location.zones.length) {
        out.zones = location.zones.map((z) => pickPresent(z, ['id', 'name']));
    }
    return Object.keys(out).length ? out : undefined;
}

function parseSensors(sensors) {
    if (!sensors) return undefined;
    const out = {};
    if (isPresent(sensors.time)) out.time = sensors.time;          // nullable

    // Battery — every sub-field is nullable; emit the object only if anything stuck.
    const battery = pickPresent(sensors.battery, ['level', 'state', 'voltage', 'charging']);
    if (battery) out.battery = battery;

    // Environmental readings — each individually nullable.
    const env = pickPresent(sensors, [
        'temperature',
        'extremeTemperature',
        'humidity',
        'atmosphericPressure',
        'lightLevel',
        'movement',
        'externalPower',
        'cellSignal',
    ]);
    if (env) out.environment = env;

    // Orientation/vibration are vector sensors. Their components are non-null
    // when the vector itself is present.
    if (sensors.orientation) out.orientation = pickPresent(sensors.orientation, ['x', 'y', 'z']);
    if (sensors.vibration) out.vibration = pickPresent(sensors.vibration, ['x', 'y', 'z']);

    // Security switch — state/activations are required when the object exists;
    // last opened/closed timestamps are nullable.
    if (sensors.securitySwitch) {
        out.securitySwitch = pickPresent(sensors.securitySwitch, ['state', 'activations', 'lastOpened', 'lastClosed']);
    }

    if (sensors.sterilisation) {
        out.sterilisation = pickPresent(sensors.sterilisation, [
            'cycleCount', 'cycleCountReadAt', 'uptimeInSeconds', 'uptimeReadAt',
        ]);
    }

    return Object.keys(out).length ? out : undefined;
}

function parseSecondaryObservation(obs) {
    // Required: id, displayId, model, reportType
    // Nullable: name, reportEventId, reportId, rssi, isPaired
    return {
        id: obs.id,
        displayId: obs.displayId,
        model: parseModel(obs.model),
        reportType: obs.reportType,
        ...pickPresent(obs, ['name', 'reportEventId', 'reportId', 'rssi', 'isPaired']),
    };
}

function parseThirdPartyObservation(obs) {
    // type-discriminated. Pass through type-specific fields verbatim and let
    // the document store keep the discriminator.
    const base = { type: obs.type, id: obs.id, displayId: obs.displayId };
    switch (obs.type) {
        case 'reelable':
            return { ...base, ...pickPresent(obs, ['temperature']) };
        case 'chorus':
            return { ...base, ...pickPresent(obs, ['temperature', 'sequenceNumber', 'batteryLevel', 'rssi', 'tamper']) };
        case 'cableSeal':
            return {
                ...base,
                ...pickPresent(obs, [
                    'state', 'open', 'highTemperature', 'lowBattery',
                    'temperature', 'batteryLevel', 'counter', 'rssi',
                ]),
            };
        default:
            // Forward-compatibility: keep the raw payload under `raw` so a
            // future beacon type still round-trips into the doc store.
            return { ...base, raw: obs };
    }
}

function parseNetworkObservations(networks) {
    if (!networks) return undefined;
    const out = {};
    if (Array.isArray(networks.wifi) && networks.wifi.length) out.wifi = networks.wifi;
    if (Array.isArray(networks.cells) && networks.cells.length) out.cells = networks.cells;
    if (Array.isArray(networks.lteNeighbourCells) && networks.lteNeighbourCells.length) {
        out.lteNeighbourCells = networks.lteNeighbourCells;
    }
    return Object.keys(out).length ? out : undefined;
}

function parseTimeSeries(timeSeries) {
    if (!timeSeries) return undefined;
    const out = {};
    for (const [key, series] of Object.entries(timeSeries)) {
        if (Array.isArray(series) && series.length) out[key] = series;
    }
    return Object.keys(out).length ? out : undefined;
}

function parseRelayedBy(reportedBy) {
    // The "reportedBy" section is set when this report was relayed — usually
    // by a primary hub on behalf of a paired secondary tag. Renamed to
    // `relayedBy` in the parsed document to make the direction obvious.
    if (!reportedBy) return undefined;
    const out = {};
    if (reportedBy.device) out.device = parseDeviceIdentity(reportedBy.device);
    if (reportedBy.report) {
        out.reportId = reportedBy.report.id;             // nullable
        if (isPresent(reportedBy.report.time)) out.reportTime = reportedBy.report.time;
    }
    if (reportedBy.app) out.app = reportedBy.app;        // nullable — mobile app metadata
    if (isPresent(reportedBy.rssi)) out.rssi = reportedBy.rssi; // nullable
    return Object.keys(out).length ? out : undefined;
}

function deriveRole(report) {
    const relayed = report.reportedBy && report.reportedBy.device && report.reportedBy.device.id;
    if (relayed) return 'secondary';
    if (Array.isArray(report.secondaryObservations) && report.secondaryObservations.length > 0) return 'primary';
    return 'standalone';
}

function parseDeviceReport(report) {
    const role = deriveRole(report);

    const doc = {
        _id: report.id,
        time: report.time,
        // nullable — rxTime may be absent if the backend hasn't stamped it.
        ...(isPresent(report.rxTime) ? { rxTime: report.rxTime } : {}),
        role,
        device: parseDeviceIdentity(report.device),
        owner: report.owner ? { id: report.owner.id, name: report.owner.name } : undefined,
        reportReasons: report.reportReasons || [],
    };

    const relayedBy = parseRelayedBy(report.reportedBy);
    if (relayedBy) doc.relayedBy = relayedBy;

    // Primary's view of paired secondaries. Each entry is a presence/RSSI
    // touch, NOT a sensor snapshot — the tag's authoritative sensor data
    // lands in its own standalone report (joined via reportId).
    if (Array.isArray(report.secondaryObservations) && report.secondaryObservations.length) {
        doc.secondaries = report.secondaryObservations.map(parseSecondaryObservation);
    }

    // Other devices that saw THIS device over BLE. Useful for crowd-sourced
    // location and proximity audit trails.
    if (Array.isArray(report.observedBy) && report.observedBy.length) {
        doc.observedBy = report.observedBy.map((o) => ({
            id: o.id,
            displayId: o.displayId,
            model: parseModel(o.model),
            ...pickPresent(o, ['name', 'reportId', 'reportTime', 'rssi']),
        }));
    }

    if (Array.isArray(report.thirdPartyObservations) && report.thirdPartyObservations.length) {
        doc.thirdPartyObservations = report.thirdPartyObservations.map(parseThirdPartyObservation);
    }

    const location = parseLocation(report.location);
    if (location) doc.location = location;

    const sensors = parseSensors(report.sensors);
    if (sensors) doc.sensors = sensors;

    if (Array.isArray(report.sensorEvents) && report.sensorEvents.length) {
        doc.sensorEvents = report.sensorEvents;
    }

    const networks = parseNetworkObservations(report.networkObservations);
    if (networks) doc.networkObservations = networks;

    const ts = parseTimeSeries(report.timeSeries);
    if (ts) doc.timeSeries = ts;

    return doc;
}

// A minimal projection suitable for the `devices` collection — the per-device
// "latest state" record. Only the bits that change frequently and are useful
// for live dashboards. Full history lives in `device_reports`.
function projectLatestState(doc) {
    const out = {
        _id: doc.device.id,
        displayId: doc.device.displayId,
        model: doc.device.model,
        labels: doc.device.labels || [],
        lastReport: { id: doc._id, time: doc.time, role: doc.role },
        updatedAt: doc.time,
    };
    if (doc.device.name) out.name = doc.device.name;
    if (doc.device.firmware) out.firmware = doc.device.firmware;
    if (doc.location) out.lastLocation = doc.location;
    if (doc.sensors) out.lastSensors = doc.sensors;
    if (doc.relayedBy) out.relayedBy = doc.relayedBy;
    return out;
}

module.exports = { parseDeviceReport, projectLatestState };
