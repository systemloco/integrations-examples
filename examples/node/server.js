const express = require('express');
const { verifySignature } = require('./lib/hmac');
const { publish } = require('./lib/queue');
const { shipments, devices, assets } = require('./lib/repos');

function createApp() {
const app = express();

// Capture the raw body so HMAC verification sees the exact bytes that were signed.
app.use(express.json({
    limit: '1mb',
    verify: (req, _res, buf) => { req.rawBody = buf; },
}));

const asyncHandler = (fn) => (req, res, next) =>
    Promise.resolve(fn(req, res, next)).catch(next);

// ============================================================================
// Shipments — low volume. Handle inline.
// ============================================================================
app.post('/webhooks/shipments', verifySignature, asyncHandler(async (req, res) => {
    const event = req.body;
    const shipmentId = event.shipment && event.shipment.id;

    if (shipmentId) {
        await shipments.appendEvent(shipmentId, event);
    }

    // A `report` event embeds a device; keep the device's latest state in sync.
    if (event.type === 'report') {
        const deviceId = event.payload && event.payload.device && event.payload.device.id;
        if (deviceId && await devices.exists(deviceId)) {
            await devices.updateLatest(deviceId, event);
        }
    }

    res.sendStatus(200);
}));

// ============================================================================
// Assets — low volume. Handle inline.
// ============================================================================
app.post('/webhooks/assets', verifySignature, asyncHandler(async (req, res) => {
    const event = req.body;
    const asset = event.asset || {};

    const shipmentId = await shipments.findIdByAssetNameOrId(asset.name, asset.id);
    if (shipmentId) {
        await shipments.appendEvent(shipmentId, event);
    }

    if (asset.id && await assets.exists(asset.id)) {
        await assets.updateLatest(asset.id, event);
    }

    res.sendStatus(200);
}));

// ============================================================================
// Device Events — medium volume, bursty. Enqueue.
// ============================================================================
app.post('/webhooks/device-events', verifySignature, asyncHandler(async (req, res) => {
    // Do NOT look up shipments, write to the database, or call downstream
    // services here. A zone change across a large fleet can produce dozens of
    // events per second — push to the queue and let the worker handle it.
    await publish('locoaware.device-events', req.body);
    res.sendStatus(202);
}));

// ============================================================================
// Device Reports V2 — highest volume. Enqueue.
// ============================================================================
app.post('/webhooks/device-reports', verifySignature, asyncHandler(async (req, res) => {
    // Firehose. A single company can push hundreds of reports per second.
    // Enqueue and ack within a few ms. The worker does the real work.
    await publish('locoaware.device-reports', req.body);
    res.sendStatus(202);
}));

app.use((err, _req, res, _next) => {
    console.error('[server] unhandled error:', err);
    res.sendStatus(500);
});

return app;
}

module.exports = { createApp };

if (require.main === module) {
    const PORT = Number(process.env.PORT || 8080);
    createApp().listen(PORT, () => {
        console.log(`[server] LocoAware webhook receiver listening on :${PORT}`);
        if (!process.env.LOCOAWARE_WEBHOOK_SECRET) {
            console.warn('[server] LOCOAWARE_WEBHOOK_SECRET is not set — HMAC verification is DISABLED');
        }
    });
}
