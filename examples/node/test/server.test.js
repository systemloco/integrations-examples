const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const http = require('node:http');

const ORIGINAL_SECRET = process.env.LOCOAWARE_WEBHOOK_SECRET;

function sign(body, secret) {
    return crypto.createHmac('sha256', secret).update(body).digest('base64');
}

function startServer() {
    // Re-require fresh so each suite picks up env changes that propagate
    // through the request lifecycle (the hmac module reads env per request).
    delete require.cache[require.resolve('../server')];
    delete require.cache[require.resolve('../lib/hmac')];
    delete require.cache[require.resolve('../lib/queue')];
    delete require.cache[require.resolve('../lib/repos')];
    const { createApp } = require('../server');
    const queue = require('../lib/queue');
    return new Promise((resolve) => {
        const server = createApp().listen(0, '127.0.0.1', () => {
            const { port } = server.address();
            resolve({ server, port, queue });
        });
    });
}

function post(port, path, body, headers = {}) {
    const data = typeof body === 'string' ? body : JSON.stringify(body);
    return new Promise((resolve, reject) => {
        const req = http.request({
            host: '127.0.0.1',
            port,
            path,
            method: 'POST',
            headers: {
                'content-type': 'application/json',
                'content-length': Buffer.byteLength(data),
                ...headers,
            },
        }, (res) => {
            const chunks = [];
            res.on('data', (c) => chunks.push(c));
            res.on('end', () => resolve({ status: res.statusCode, body: Buffer.concat(chunks).toString() }));
        });
        req.on('error', reject);
        req.write(data);
        req.end();
    });
}

test('endpoints with no secret configured', async (t) => {
    delete process.env.LOCOAWARE_WEBHOOK_SECRET;
    const { server, port, queue } = await startServer();
    t.after(() => {
        server.close();
        if (ORIGINAL_SECRET === undefined) delete process.env.LOCOAWARE_WEBHOOK_SECRET;
        else process.env.LOCOAWARE_WEBHOOK_SECRET = ORIGINAL_SECRET;
    });

    await t.test('shipments returns 200', async () => {
        const r = await post(port, '/webhooks/shipments', {
            id: 'evt_1', type: 'positionReport',
            shipment: { id: 'ship_1' },
        });
        assert.equal(r.status, 200);
    });

    await t.test('shipments report event syncs the embedded device', async () => {
        const r = await post(port, '/webhooks/shipments', {
            id: 'evt_2', type: 'report',
            shipment: { id: 'ship_2' },
            payload: { device: { id: 'dev_2' } },
        });
        assert.equal(r.status, 200);
    });

    await t.test('assets returns 200', async () => {
        const r = await post(port, '/webhooks/assets', {
            id: 'evt_3', type: 'positionReport',
            asset: { id: 'asset_3', name: 'pallet-3' },
        });
        assert.equal(r.status, 200);
    });

    await t.test('device-events returns 202 and enqueues', async () => {
        const received = new Promise((resolve) => {
            queue.subscribe('locoaware.device-events', (msg) => resolve(msg));
        });
        const r = await post(port, '/webhooks/device-events', {
            id: 'evt_4', device: { id: 'dev_4', name: 'ship_4' },
        });
        assert.equal(r.status, 202);
        const msg = await received;
        assert.equal(msg.id, 'evt_4');
    });

    await t.test('device-reports returns 202 and enqueues', async () => {
        const received = new Promise((resolve) => {
            queue.subscribe('locoaware.device-reports', (msg) => resolve(msg));
        });
        const r = await post(port, '/webhooks/device-reports', {
            device: { id: 'dev_5', name: 'ship_5' },
            sensors: [],
        });
        assert.equal(r.status, 202);
        const msg = await received;
        assert.deepEqual(msg.device, { id: 'dev_5', name: 'ship_5' });
    });
});

test('endpoints with a secret configured', async (t) => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = 'shhh';
    const { server, port } = await startServer();
    t.after(() => {
        server.close();
        if (ORIGINAL_SECRET === undefined) delete process.env.LOCOAWARE_WEBHOOK_SECRET;
        else process.env.LOCOAWARE_WEBHOOK_SECRET = ORIGINAL_SECRET;
    });

    await t.test('returns 401 without a signature header', async () => {
        const r = await post(port, '/webhooks/shipments', { shipment: { id: 'x' } });
        assert.equal(r.status, 401);
    });

    await t.test('returns 401 with a wrong signature', async () => {
        const body = JSON.stringify({ shipment: { id: 'x' } });
        const r = await post(port, '/webhooks/shipments', body, {
            'x-locoaware-signature': sign(body, 'wrong-secret'),
        });
        assert.equal(r.status, 401);
    });

    await t.test('accepts a correctly signed shipment event', async () => {
        const body = JSON.stringify({ id: 'evt_signed', shipment: { id: 'ship_signed' } });
        const r = await post(port, '/webhooks/shipments', body, {
            'x-locoaware-signature': sign(body, 'shhh'),
        });
        assert.equal(r.status, 200);
    });

    await t.test('accepts a correctly signed device-report and enqueues', async () => {
        const body = JSON.stringify({ device: { id: 'dev_signed' } });
        const r = await post(port, '/webhooks/device-reports', body, {
            'x-locoaware-signature': sign(body, 'shhh'),
        });
        assert.equal(r.status, 202);
    });
});
