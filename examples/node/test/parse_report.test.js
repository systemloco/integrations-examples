const test = require('node:test');
const assert = require('node:assert/strict');
const { parseDeviceReport, projectLatestState } = require('../lib/parse_report');

// ─── Fixtures ────────────────────────────────────────────────────────────────
// Trimmed versions of the payloads in Device_Reports_V2_Integration_Guide.md.

const primaryReport = {
    id: '6612b1a0e4b0a1b2c3d4e5f6',
    time: '2026-04-09T10:47:58.612Z',
    rxTime: '2026-04-09T10:47:58.716Z',
    device: {
        id: '72308628874417738',
        displayId: 'HGD-00417738',
        model: { name: 'HGD4', family: 'locoTrack', product: 'LocoTrack', version: 4, revision: 1 },
        labels: ['cold-chain', 'us-west'],
    },
    owner: { id: '660fa1b2c3d4e5f6a7b8c9d0', name: 'Acme Logistics' },
    reportReasons: ['report'],
    location: {
        type: 'wifi',
        time: '2026-04-09T10:47:58.612Z',
        global: { lat: 34.079725, lon: -117.224535, cep: 28.0 },
    },
    sensors: {
        time: '2026-04-09T10:47:58.612Z',
        battery: { level: 100, state: 'good', voltage: 6.226, charging: false },
        temperature: 25.1,
        atmosphericPressure: 968.8,
        lightLevel: 67,
        orientation: { x: 0.026, y: 0.021, z: -0.996 },
        vibration: { x: 0.016, y: 0.015, z: 0.013 },
        movement: 'stationary',
        externalPower: false,
    },
    secondaryObservations: [
        {
            id: '72308628872452464',
            displayId: 'TAG-72452464',
            model: { name: 'E1BL', family: 'locoCard', product: 'LocoCard', version: 1, revision: 0 },
            reportType: 'presence',
            reportId: '6612b1a0e4b0a1b2c3d4e5f7',
            rssi: -53,
            isPaired: true,
        },
        {
            id: '72308628873734501',
            displayId: 'TAG-73734501',
            model: { name: 'E1BL', family: 'locoCard', product: 'LocoCard', version: 1, revision: 0 },
            reportType: 'presence',
            reportId: '6612b1a0e4b0a1b2c3d4e5f8',
            rssi: -67,
            isPaired: true,
        },
    ],
    thirdPartyObservations: [
        {
            type: 'cableSeal',
            id: 'A2:A2:A2:00:07:AB',
            displayId: 'A2A2A20007AB',
            state: 'closed', open: false, highTemperature: false, lowBattery: false,
            temperature: 21, batteryLevel: 82, counter: 41806, rssi: -50,
        },
    ],
    networkObservations: {
        wifi: [{ mac: '70:0f:6a:e9:f0:20', ssid: 'XPOSCWPA', rssi: -59 }],
    },
};

const secondaryReport = {
    id: '6612b1a0e4b0a1b2c3d4e5f7',
    time: '2026-04-09T10:48:51.981Z',
    rxTime: '2026-04-09T10:48:52.207Z',
    device: {
        id: '72308628872452464',
        displayId: 'TAG-72452464',
        model: { name: 'E1BL', family: 'locoCard', product: 'LocoCard', version: 1, revision: 0 },
        labels: [],
    },
    owner: { id: '660fa1b2c3d4e5f6a7b8c9d0', name: 'Acme Logistics' },
    reportedBy: {
        device: {
            id: '72308628874417738',
            displayId: 'HGD-00417738',
            model: { name: 'HGD4', family: 'locoTrack', product: 'LocoTrack', version: 4, revision: 1 },
            labels: [],
        },
        report: { id: '6612b1a0e4b0a1b2c3d4e5f6', time: '2026-04-09T10:47:58.612Z' },
        rssi: -53,
    },
    location: { type: 'wifi', time: '2026-04-09T10:48:51.981Z', global: { lat: 34.080073, lon: -117.224648, cep: 37.0 } },
    sensors: { battery: { level: 80, state: 'good', charging: false }, externalPower: false },
    timeSeries: {
        temperature: [
            { time: '2026-04-09T10:36:42.981Z', value: 22.6 },
            { time: '2026-04-09T10:51:42.981Z', value: 22.6 },
        ],
    },
};

const sparseReport = {
    id: 'rpt-sparse',
    time: '2026-04-09T10:00:00.000Z',
    // No rxTime, no location, no sensors, no observations.
    device: {
        id: 'dev-sparse',
        displayId: 'DS-1',
        model: { name: 'HGD4', family: 'locoTrack', product: 'LocoTrack', version: 4, revision: 1 },
        labels: [],
    },
    owner: { id: 'own-1', name: 'Owner' },
};

// ─── Tests ────────────────────────────────────────────────────────────────────

test('parser identifies a primary report by its secondaryObservations', () => {
    const doc = parseDeviceReport(primaryReport);
    assert.equal(doc.role, 'primary');
    assert.equal(doc._id, primaryReport.id);
    assert.equal(doc.secondaries.length, 2);
    assert.equal(doc.secondaries[0].id, '72308628872452464');
    assert.equal(doc.secondaries[0].reportId, '6612b1a0e4b0a1b2c3d4e5f7');
    assert.equal(doc.secondaries[0].isPaired, true);
});

test('parser identifies a secondary report by reportedBy.device', () => {
    const doc = parseDeviceReport(secondaryReport);
    assert.equal(doc.role, 'secondary');
    assert.equal(doc._id, secondaryReport.id);
    assert.equal(doc.relayedBy.device.id, '72308628874417738');
    assert.equal(doc.relayedBy.reportId, '6612b1a0e4b0a1b2c3d4e5f6');
    assert.equal(doc.relayedBy.rssi, -53);
});

test('primary→secondary link can be traced both ways', () => {
    const primary = parseDeviceReport(primaryReport);
    const secondary = parseDeviceReport(secondaryReport);

    // primary.secondaries[i].reportId === secondary._id
    const matchingObs = primary.secondaries.find((s) => s.reportId === secondary._id);
    assert.ok(matchingObs, 'primary should list the secondary by its report id');
    assert.equal(matchingObs.id, secondary.device.id);

    // secondary.relayedBy.reportId === primary._id
    assert.equal(secondary.relayedBy.reportId, primary._id);
    assert.equal(secondary.relayedBy.device.id, primary.device.id);
});

test('standalone report has no secondaries and no relayedBy', () => {
    const doc = parseDeviceReport(sparseReport);
    assert.equal(doc.role, 'standalone');
    assert.equal(doc.secondaries, undefined);
    assert.equal(doc.relayedBy, undefined);
});

test('sparse report omits absent fields entirely (no explicit nulls)', () => {
    const doc = parseDeviceReport(sparseReport);
    assert.ok(!('rxTime' in doc));
    assert.ok(!('location' in doc));
    assert.ok(!('sensors' in doc));
    assert.ok(!('relayedBy' in doc));
    assert.ok(!('thirdPartyObservations' in doc));
    assert.ok(!('timeSeries' in doc));
    // Required fields are present.
    assert.equal(doc._id, 'rpt-sparse');
    assert.equal(doc.device.id, 'dev-sparse');
    assert.deepEqual(doc.device.labels, []);
});

test('sensors are grouped into battery / environment / orientation / vibration', () => {
    const doc = parseDeviceReport(primaryReport);
    assert.equal(doc.sensors.battery.level, 100);
    assert.equal(doc.sensors.battery.voltage, 6.226);
    assert.equal(doc.sensors.environment.temperature, 25.1);
    assert.equal(doc.sensors.environment.atmosphericPressure, 968.8);
    assert.equal(doc.sensors.environment.lightLevel, 67);
    assert.equal(doc.sensors.environment.movement, 'stationary');
    assert.deepEqual(doc.sensors.orientation, { x: 0.026, y: 0.021, z: -0.996 });
    assert.deepEqual(doc.sensors.vibration, { x: 0.016, y: 0.015, z: 0.013 });
});

test('environment object is omitted when no environmental sensors are present', () => {
    const doc = parseDeviceReport(secondaryReport);
    // battery exists, environment does not — secondary report only had battery + externalPower.
    assert.ok(doc.sensors.battery);
    assert.equal(doc.sensors.environment.externalPower, false);
    // Sanity: temperature absent on the secondary's sensor snapshot.
    assert.ok(!('temperature' in (doc.sensors.environment || {})));
});

test('third-party observations preserve discriminator and type-specific fields', () => {
    const doc = parseDeviceReport(primaryReport);
    assert.equal(doc.thirdPartyObservations.length, 1);
    const seal = doc.thirdPartyObservations[0];
    assert.equal(seal.type, 'cableSeal');
    assert.equal(seal.state, 'closed');
    assert.equal(seal.temperature, 21);
    assert.equal(seal.counter, 41806);
});

test('unknown third-party beacon type falls back to raw passthrough', () => {
    const doc = parseDeviceReport({
        ...sparseReport,
        thirdPartyObservations: [{ type: 'futureBeacon', id: 'F1', displayId: 'F1', mystery: true }],
    });
    const beacon = doc.thirdPartyObservations[0];
    assert.equal(beacon.type, 'futureBeacon');
    assert.equal(beacon.raw.mystery, true);
});

test('time series passes through arrays unchanged and omits empty ones', () => {
    const doc = parseDeviceReport(secondaryReport);
    assert.equal(doc.timeSeries.temperature.length, 2);
    assert.equal(doc.timeSeries.temperature[0].value, 22.6);

    const empty = parseDeviceReport({ ...sparseReport, timeSeries: { humidity: [] } });
    assert.equal(empty.timeSeries, undefined);
});

test('projectLatestState returns a compact per-device snapshot keyed by device id', () => {
    const doc = parseDeviceReport(primaryReport);
    const state = projectLatestState(doc);
    assert.equal(state._id, doc.device.id);
    assert.equal(state.lastReport.id, doc._id);
    assert.equal(state.lastReport.role, 'primary');
    assert.equal(state.updatedAt, doc.time);
    assert.ok(state.lastSensors.battery);
    assert.ok(state.lastLocation.global);
});

test('projectLatestState omits absent optional fields', () => {
    const doc = parseDeviceReport(sparseReport);
    const state = projectLatestState(doc);
    assert.equal(state._id, 'dev-sparse');
    assert.ok(!('lastLocation' in state));
    assert.ok(!('lastSensors' in state));
    assert.ok(!('relayedBy' in state));
});
