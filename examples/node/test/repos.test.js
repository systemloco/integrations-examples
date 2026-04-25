const test = require('node:test');
const assert = require('node:assert/strict');
const { shipments, devices, assets } = require('../lib/repos');

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

test('shipments lookups return null in the stand-in', async () => {
    assert.equal(await shipments.findIdByDeviceNameOrId('ship_1', 'dev_1'), null);
    assert.equal(await shipments.findIdByAssetNameOrId('pallet-1', 'asset_1'), null);
});

test('shipments.appendEvent resolves without throwing', async () => {
    await shipments.appendEvent('ship_1', { id: 'evt_1', type: 'positionReport' });
});

test('devices.updateLatest resolves without throwing', async () => {
    await devices.updateLatest('dev_1', { id: 'evt_1' });
});
