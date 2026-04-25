<?php

declare(strict_types=1);

namespace LocoAware\Webhook\Tests;

use PHPUnit\Framework\TestCase;
use Slim\Psr7\Factory\ServerRequestFactory;
use Slim\Psr7\Factory\StreamFactory;
use function LocoAware\Webhook\createApp;

require_once __DIR__ . '/Doubles.php';

class WebhookEndpointsTest extends TestCase
{
    private RecordingQueueClient $queue;
    private RecordingShipments $shipments;
    private RecordingDevices $devices;
    private RecordingAssets $assets;

    protected function setUp(): void
    {
        putenv('LOCOAWARE_WEBHOOK_SECRET');
        $this->queue     = new RecordingQueueClient();
        $this->shipments = new RecordingShipments();
        $this->devices   = new RecordingDevices();
        $this->assets    = new RecordingAssets();
    }

    private function dispatch(string $method, string $path, string $body, array $headers = []): \Psr\Http\Message\ResponseInterface
    {
        $app = createApp($this->queue, $this->shipments, $this->devices, $this->assets);
        $request = (new ServerRequestFactory())->createServerRequest($method, $path)
            ->withHeader('Content-Type', 'application/json')
            ->withBody((new StreamFactory())->createStream($body));
        foreach ($headers as $name => $value) {
            $request = $request->withHeader($name, $value);
        }
        return $app->handle($request);
    }

    public function testShipmentsEndpointReturns200(): void
    {
        $body = json_encode(['id' => 'evt_1', 'type' => 'positionReport', 'shipment' => ['id' => 'ship_1']]);
        $resp = $this->dispatch('POST', '/webhooks/shipments', $body);
        $this->assertSame(200, $resp->getStatusCode());
        $this->assertCount(1, $this->shipments->appended);
        $this->assertSame('ship_1', $this->shipments->appended[0]['shipmentId']);
    }

    public function testShipmentReportSyncsTheEmbeddedDevice(): void
    {
        $body = json_encode([
            'id' => 'evt_2', 'type' => 'report',
            'shipment' => ['id' => 'ship_2'],
            'payload' => ['device' => ['id' => 'dev_2']],
        ]);
        $resp = $this->dispatch('POST', '/webhooks/shipments', $body);
        $this->assertSame(200, $resp->getStatusCode());
        $this->assertContains('dev_2', $this->devices->updates);
    }

    public function testAssetsEndpointReturns200AndLooksUpShipment(): void
    {
        $body = json_encode(['id' => 'evt_3', 'asset' => ['id' => 'asset_3', 'name' => 'pallet-3']]);
        $resp = $this->dispatch('POST', '/webhooks/assets', $body);
        $this->assertSame(200, $resp->getStatusCode());
        $this->assertSame([['pallet-3', 'asset_3']], $this->shipments->assetLookups);
    }

    public function testDeviceEventsEndpointReturns202AndEnqueues(): void
    {
        $body = json_encode(['id' => 'evt_4', 'device' => ['id' => 'dev_4']]);
        $resp = $this->dispatch('POST', '/webhooks/device-events', $body);
        $this->assertSame(202, $resp->getStatusCode());
        $this->assertCount(1, $this->queue->published);
        $this->assertSame('locoaware.device-events', $this->queue->published[0]['topic']);
        $this->assertSame($body, $this->queue->published[0]['body']);
    }

    public function testDeviceReportsEndpointReturns202AndEnqueues(): void
    {
        $body = json_encode(['device' => ['id' => 'dev_5', 'name' => 'ship_5']]);
        $resp = $this->dispatch('POST', '/webhooks/device-reports', $body);
        $this->assertSame(202, $resp->getStatusCode());
        $this->assertCount(1, $this->queue->published);
        $this->assertSame('locoaware.device-reports', $this->queue->published[0]['topic']);
    }
}
