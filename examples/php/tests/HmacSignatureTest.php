<?php

declare(strict_types=1);

namespace LocoAware\Webhook\Tests;

use PHPUnit\Framework\TestCase;
use Slim\Psr7\Factory\ServerRequestFactory;
use Slim\Psr7\Factory\StreamFactory;
use function LocoAware\Webhook\createApp;

require_once __DIR__ . '/Doubles.php';

class HmacSignatureTest extends TestCase
{
    private const SECRET = 'shhh';

    private RecordingQueueClient $queue;
    private RecordingShipments $shipments;
    private RecordingDevices $devices;
    private RecordingAssets $assets;

    protected function setUp(): void
    {
        putenv('LOCOAWARE_WEBHOOK_SECRET=' . self::SECRET);
        $this->queue     = new RecordingQueueClient();
        $this->shipments = new RecordingShipments();
        $this->devices   = new RecordingDevices();
        $this->assets    = new RecordingAssets();
    }

    protected function tearDown(): void
    {
        putenv('LOCOAWARE_WEBHOOK_SECRET');
    }

    private function sign(string $body, string $secret): string
    {
        return base64_encode(hash_hmac('sha256', $body, $secret, true));
    }

    private function dispatch(string $body, array $headers = []): \Psr\Http\Message\ResponseInterface
    {
        $app = createApp($this->queue, $this->shipments, $this->devices, $this->assets);
        $request = (new ServerRequestFactory())->createServerRequest('POST', '/webhooks/shipments')
            ->withHeader('Content-Type', 'application/json')
            ->withBody((new StreamFactory())->createStream($body));
        foreach ($headers as $name => $value) {
            $request = $request->withHeader($name, $value);
        }
        return $app->handle($request);
    }

    public function testRejectsRequestWithoutSignatureHeader(): void
    {
        $resp = $this->dispatch(json_encode(['shipment' => ['id' => 'x']]));
        $this->assertSame(401, $resp->getStatusCode());
    }

    public function testRejectsRequestWithBadSignature(): void
    {
        $body = json_encode(['shipment' => ['id' => 'x']]);
        $resp = $this->dispatch($body, [
            'X-LocoAware-Signature' => $this->sign($body, 'wrong-secret'),
        ]);
        $this->assertSame(401, $resp->getStatusCode());
    }

    public function testAcceptsCorrectlySignedRequest(): void
    {
        $body = json_encode(['id' => 'evt_signed', 'shipment' => ['id' => 'ship_signed']]);
        $resp = $this->dispatch($body, [
            'X-LocoAware-Signature' => $this->sign($body, self::SECRET),
        ]);
        $this->assertSame(200, $resp->getStatusCode());
    }
}
