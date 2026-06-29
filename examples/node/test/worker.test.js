const test = require('node:test');
const assert = require('node:assert/strict');

function freshModules() {
    delete require.cache[require.resolve('../lib/repos')];
    delete require.cache[require.resolve('../lib/parse_report')];
    delete require.cache[require.resolve('../lib/queue')];
    delete require.cache[require.resolve('../worker')];
    return {
        repos: require('../lib/repos'),
        worker: require('../worker'),
    };
}

const primary = {
    id: 'rpt-primary-1',
    time: '2026-04-09T10:47:58.612Z',
    device: { id: 'dev-primary', displayId: 'HGD-1', model: { family: 'locoTrack', name: 'HGD4', product: 'LocoTrack', version: 4, revision: 1 }, labels: [] },
    owner: { id: 'own-1', name: 'Acme' },
    location: { type: 'wifi', global: { lat: 1, lon: 2 } },
    sensors: { battery: { level: 90 }, temperature: 20 },
    secondaryObservations: [
        { id: 'dev-tag-A', displayId: 'TAG-A', model: { family: 'locoCard', name: 'E1BL', product: 'LocoCard', version: 1, revision: 0 }, reportType: 'presence', reportId: 'rpt-tag-A', rssi: -50, isPaired: true },
        { id: 'dev-tag-B', displayId: 'TAG-B', model: { family: 'locoCard', name: 'E1BL', product: 'LocoCard', version: 1, revision: 0 }, reportType: 'event',    reportId: 'rpt-tag-B', rssi: -70, isPaired: true },
    ],
};

const secondary = {
    id: 'rpt-tag-A',
    time: '2026-04-09T10:48:51.000Z',
    device: { id: 'dev-tag-A', displayId: 'TAG-A', model: { family: 'locoCard', name: 'E1BL', product: 'LocoCard', version: 1, revision: 0 }, labels: [] },
    owner: { id: 'own-1', name: 'Acme' },
    reportedBy: {
        device: { id: 'dev-primary', displayId: 'HGD-1', model: { family: 'locoTrack', name: 'HGD4', product: 'LocoTrack', version: 4, revision: 1 } },
        report: { id: 'rpt-primary-1', time: '2026-04-09T10:47:58.612Z' },
        rssi: -50,
    },
    sensors: { battery: { level: 80 } },
    timeSeries: { temperature: [{ time: '2026-04-09T10:36:00.000Z', value: 22.6 }] },
};

test('worker stores the primary report and presence-touches each secondary', async () => {
    const { repos, worker } = freshModules();
    repos._resetAll();

    await worker.handleDeviceReport(primary);

    const stored = repos.deviceReports._get('rpt-primary-1');
    assert.equal(stored.role, 'primary');
    assert.equal(stored.secondaries.length, 2);

    const primaryDev = repos.devices._get('dev-primary');
    assert.equal(primaryDev.lastReport.id, 'rpt-primary-1');
    assert.equal(primaryDev.lastReport.role, 'primary');
    assert.equal(primaryDev.lastSensors.battery.level, 90);

    // Both secondaries should have a presence touch — but NOT a lastSensors
    // entry, because the primary's view is presence-only.
    const tagA = repos.devices._get('dev-tag-A');
    const tagB = repos.devices._get('dev-tag-B');
    assert.equal(tagA.lastSeenBy.primaryDeviceId, 'dev-primary');
    assert.equal(tagA.lastSeenBy.rssi, -50);
    assert.equal(tagB.lastSeenBy.primaryDeviceId, 'dev-primary');
    assert.equal(tagB.lastSeenBy.rssi, -70);
    assert.ok(!('lastSensors' in tagA), 'primary presence touch must not write sensor data');
});

test('worker stores the secondary report with authoritative tag sensors', async () => {
    const { repos, worker } = freshModules();
    repos._resetAll();

    // Order doesn't matter; deliveries arrive independently per the guide.
    await worker.handleDeviceReport(secondary);
    await worker.handleDeviceReport(primary);

    const tagA = repos.devices._get('dev-tag-A');
    // Tag's own report wrote sensors before the primary's presence touch
    // landed. The primary's touch should not clobber lastSensors.
    assert.equal(tagA.lastSensors.battery.level, 80);
    assert.equal(tagA.lastSeenBy.primaryDeviceId, 'dev-primary');
    assert.equal(tagA.lastReport.id, 'rpt-tag-A');
    assert.equal(tagA.lastReport.role, 'secondary');
});

test('worker falls back to the relay primary when the tag has no shipment binding', async () => {
    const { repos, worker } = freshModules();
    repos._resetAll();
    repos.shipments._seed('ship-7', { boundDeviceIds: ['dev-primary'] });

    await worker.handleDeviceReport(secondary);

    const ship = repos.shipments._get('ship-7');
    assert.equal(ship.events.length, 1);
    assert.equal(ship.events[0]._id, 'rpt-tag-A');
});

test('worker appends to a shipment matched by device name (the conventional shipment carrier)', async () => {
    const { repos, worker } = freshModules();
    repos._resetAll();
    repos.shipments._seed('ship-42', { boundDeviceNames: ['SHIP-42'] });

    await worker.handleDeviceReport({
        ...primary,
        id: 'rpt-with-name',
        device: { ...primary.device, name: 'SHIP-42' },
    });

    const ship = repos.shipments._get('ship-42');
    assert.equal(ship.events.length, 1);
});

test('worker is null-safe when the report has nothing optional', async () => {
    const { repos, worker } = freshModules();
    repos._resetAll();
    await worker.handleDeviceReport({
        id: 'rpt-empty',
        time: '2026-04-09T10:00:00.000Z',
        device: { id: 'dev-empty', displayId: 'X', model: { family: 'locoTrack', name: 'HGD4', product: 'LocoTrack', version: 4, revision: 1 }, labels: [] },
        owner: { id: 'own-1', name: 'Acme' },
    });
    const stored = repos.deviceReports._get('rpt-empty');
    assert.equal(stored.role, 'standalone');
    assert.equal(repos.devices._get('dev-empty')._id, 'dev-empty');
});
