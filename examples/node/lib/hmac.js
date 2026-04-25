const crypto = require('crypto');

function verifySignature(req, res, next) {
    const secret = process.env.LOCOAWARE_WEBHOOK_SECRET;
    if (!secret) return next();

    const header = (process.env.LOCOAWARE_SIGNATURE_HEADER || 'x-locoaware-signature').toLowerCase();
    const received = req.get(header);
    if (!received) return res.status(401).send('missing signature');

    // LocoAware sends base64(HMAC-SHA256(body, secret)) in the signature header.
    const expected = crypto
        .createHmac('sha256', secret)
        .update(req.rawBody)
        .digest();

    let receivedBytes;
    try {
        receivedBytes = Buffer.from(received, 'base64');
    } catch {
        return res.status(401).send('bad signature');
    }
    if (receivedBytes.length !== expected.length || !crypto.timingSafeEqual(receivedBytes, expected)) {
        return res.status(401).send('bad signature');
    }

    next();
}

module.exports = { verifySignature };
