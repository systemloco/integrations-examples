const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('node:crypto');
const { verifySignature } = require('../lib/hmac');

const SECRET = 'shhh';
const ORIGINAL_SECRET = process.env.LOCOAWARE_WEBHOOK_SECRET;

function sign(body, secret = SECRET) {
    return crypto.createHmac('sha256', secret).update(body).digest('base64');
}

function makeReq({ body = Buffer.from('{}'), headers = {} } = {}) {
    return {
        rawBody: body,
        get(name) { return headers[name.toLowerCase()]; },
    };
}

function makeRes() {
    const res = {
        statusCode: 200,
        body: undefined,
        status(code) { this.statusCode = code; return this; },
        send(b) { this.body = b; return this; },
    };
    return res;
}

test.afterEach(() => {
    if (ORIGINAL_SECRET === undefined) delete process.env.LOCOAWARE_WEBHOOK_SECRET;
    else process.env.LOCOAWARE_WEBHOOK_SECRET = ORIGINAL_SECRET;
});

test('passes through when no secret is configured', () => {
    delete process.env.LOCOAWARE_WEBHOOK_SECRET;
    let calledNext = false;
    verifySignature(makeReq(), makeRes(), () => { calledNext = true; });
    assert.equal(calledNext, true);
});

test('returns 401 when signature header missing', () => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = SECRET;
    const res = makeRes();
    let calledNext = false;
    verifySignature(makeReq(), res, () => { calledNext = true; });
    assert.equal(calledNext, false);
    assert.equal(res.statusCode, 401);
    assert.equal(res.body, 'missing signature');
});

test('returns 401 when signature does not match', () => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = SECRET;
    const body = Buffer.from('{"hello":"world"}');
    const wrong = sign(body, 'a-different-secret');
    const res = makeRes();
    let calledNext = false;
    verifySignature(
        makeReq({ body, headers: { 'x-locoaware-signature': wrong } }),
        res,
        () => { calledNext = true; },
    );
    assert.equal(calledNext, false);
    assert.equal(res.statusCode, 401);
    assert.equal(res.body, 'bad signature');
});

test('returns 401 when signature header is not valid base64', () => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = SECRET;
    const res = makeRes();
    let calledNext = false;
    verifySignature(
        makeReq({ headers: { 'x-locoaware-signature': '!!!!not-base64!!!!' } }),
        res,
        () => { calledNext = true; },
    );
    assert.equal(calledNext, false);
    assert.equal(res.statusCode, 401);
});

test('passes through with a valid signature', () => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = SECRET;
    const body = Buffer.from('{"hello":"world"}');
    const res = makeRes();
    let calledNext = false;
    verifySignature(
        makeReq({ body, headers: { 'x-locoaware-signature': sign(body) } }),
        res,
        () => { calledNext = true; },
    );
    assert.equal(calledNext, true);
    assert.equal(res.statusCode, 200);
});

test('honours a custom signature header name', () => {
    process.env.LOCOAWARE_WEBHOOK_SECRET = SECRET;
    process.env.LOCOAWARE_SIGNATURE_HEADER = 'X-Custom-Sig';
    try {
        const body = Buffer.from('{"a":1}');
        const res = makeRes();
        let calledNext = false;
        verifySignature(
            makeReq({ body, headers: { 'x-custom-sig': sign(body) } }),
            res,
            () => { calledNext = true; },
        );
        assert.equal(calledNext, true);
    } finally {
        delete process.env.LOCOAWARE_SIGNATURE_HEADER;
    }
});
