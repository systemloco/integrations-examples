const test = require('node:test');
const assert = require('node:assert/strict');
const { shipments, devices, assets, deviceReports, _resetAll } = require('../lib/repos');

test('devices.exists returns true for a non-empty id', async () => {
    assert.equal(await devices.exists('dev_1'), true);
});

test('devices.exists returns false for an empty id', async () => {
    assert.equal(await devices.exists(''), false);
    assert.equal(await devices.exists(null), false);
    assert.equal(await devices.exists(undefined), false);
});

test('assets.exists returns true for a non-empty id', async () => {
    assert.equal(await assets.exists('asset_1'), true);
});

test('assets.exists returns false for an empty id', async () => {
    assert.equal(await assets.exists(''), false);
});

test('shipment lookups return null when nothing is seeded', async () => {
    _resetAll();
    assert.equal(await shipments.findIdByDeviceNameOrId('ship_1', 'dev_1'), null);
    assert.equal(await shipments.findIdByAssetNameOrId('pallet-1', 'asset_1'), null);
});

test('shipment lookups match by either bound device name or device id', async () => {
    _resetAll();
    shipments._seed('ship_a', { boundDeviceNames: ['SHIP_A'] });
    shipments._seed('ship_b', { boundDeviceIds: ['dev_b'] });
    assert.equal(await shipments.findIdByDeviceNameOrId('SHIP_A', null), 'ship_a');
    assert.equal(await shipments.findIdByDeviceNameOrId(null, 'dev_b'), 'ship_b');
});

test('shipments.appendEvent accumulates events under the shipment id', async () => {
    _resetAll();
    await shipments.appendEvent('ship_x', { _id: 'evt_1' });
    await shipments.appendEvent('ship_x', { _id: 'evt_2' });
    const stored = shipments._get('ship_x');
    assert.equal(stored.events.length, 2);
    assert.equal(stored.events[1]._id, 'evt_2');
});

test('devices.upsertLatest merges into the existing document', async () => {
    _resetAll();
    await devices.upsertLatest('dev_1', { lastSensors: { battery: { level: 90 } } });
    await devices.upsertLatest('dev_1', { lastLocation: { type: 'wifi' } });
    const stored = devices._get('dev_1');
    assert.equal(stored.lastSensors.battery.level, 90);
    assert.equal(stored.lastLocation.type, 'wifi');
});

test('devices.touchObservation writes lastSeenBy without clobbering lastSensors', async () => {
    _resetAll();
    await devices.upsertLatest('dev_tag', { lastSensors: { battery: { level: 80 } } });
    await devices.touchObservation('dev_tag', { primaryDeviceId: 'dev_primary', rssi: -50 });
    const stored = devices._get('dev_tag');
    assert.equal(stored.lastSensors.battery.level, 80);
    assert.equal(stored.lastSeenBy.primaryDeviceId, 'dev_primary');
    assert.equal(stored.lastSeenBy.rssi, -50);
});

test('deviceReports.insert is keyed by report id for re-delivery idempotency', async () => {
    _resetAll();
    await deviceReports.insert({ _id: 'rpt_1', role: 'primary' });
    await deviceReports.insert({ _id: 'rpt_1', role: 'primary' });
    assert.equal(deviceReports._all().length, 1);
});
