<?php

declare(strict_types=1);

namespace LocoAware\Webhook\Tests;

use LocoAware\Webhook\DeviceReports;
use LocoAware\Webhook\Devices;
use LocoAware\Webhook\Shipments;
use LocoAware\Webhook\Worker;
use PHPUnit\Framework\TestCase;

class WorkerTest extends TestCase
{
    private DeviceReports $deviceReports;
    private Shipments $shipments;
    private Devices $devices;
    private Worker $worker;

    private const PRIMARY = [
        'id' => 'rpt-primary-1',
        'time' => '2026-04-09T10:47:58.612Z',
        'device' => [
            'id' => 'dev-primary', 'displayId' => 'HGD-1',
            'model' => ['family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1],
            'labels' => [],
        ],
        'owner' => ['id' => 'own-1', 'name' => 'Acme'],
        'sensors' => ['battery' => ['level' => 90], 'temperature' => 20],
        'secondaryObservations' => [
            ['id' => 'dev-tag-A', 'displayId' => 'TAG-A',
             'model' => ['family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0],
             'reportType' => 'presence', 'reportId' => 'rpt-tag-A', 'rssi' => -50, 'isPaired' => true],
            ['id' => 'dev-tag-B', 'displayId' => 'TAG-B',
             'model' => ['family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0],
             'reportType' => 'event', 'reportId' => 'rpt-tag-B', 'rssi' => -70, 'isPaired' => true],
        ],
    ];

    private const SECONDARY = [
        'id' => 'rpt-tag-A',
        'time' => '2026-04-09T10:48:51.000Z',
        'device' => [
            'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
            'model' => ['family' => 'locoCard', 'name' => 'E1BL', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0],
            'labels' => [],
        ],
        'owner' => ['id' => 'own-1', 'name' => 'Acme'],
        'reportedBy' => [
            'device' => ['id' => 'dev-primary', 'displayId' => 'HGD-1',
                         'model' => ['family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1]],
            'report' => ['id' => 'rpt-primary-1', 'time' => '2026-04-09T10:47:58.612Z'],
            'rssi' => -50,
        ],
        'sensors' => ['battery' => ['level' => 80]],
    ];

    protected function setUp(): void
    {
        $this->deviceReports = new DeviceReports();
        $this->shipments = new Shipments();
        $this->devices = new Devices();
        $this->worker = new Worker($this->deviceReports, $this->shipments, $this->devices);
    }

    public function testPrimaryStoresReportAndTouchesEachSecondary(): void
    {
        $this->worker->handleDeviceReport(self::PRIMARY);

        $stored = $this->deviceReports->get('rpt-primary-1');
        $this->assertSame('primary', $stored['role']);
        $this->assertCount(2, $stored['secondaries']);

        $primaryDev = $this->devices->get('dev-primary');
        $this->assertSame('rpt-primary-1', $primaryDev['lastReport']['id']);
        $this->assertSame(90, $primaryDev['lastSensors']['battery']['level']);

        $tagA = $this->devices->get('dev-tag-A');
        $tagB = $this->devices->get('dev-tag-B');
        $this->assertSame('dev-primary', $tagA['lastSeenBy']['primaryDeviceId']);
        $this->assertSame(-50, $tagA['lastSeenBy']['rssi']);
        $this->assertSame(-70, $tagB['lastSeenBy']['rssi']);
        $this->assertArrayNotHasKey('lastSensors', $tagA, 'primary presence touch must not write sensor data');
    }

    public function testSecondaryWritesAuthoritativeSensorsAndPrimaryDoesNotClobber(): void
    {
        $this->worker->handleDeviceReport(self::SECONDARY);
        $this->worker->handleDeviceReport(self::PRIMARY);

        $tagA = $this->devices->get('dev-tag-A');
        $this->assertSame(80, $tagA['lastSensors']['battery']['level']);
        $this->assertSame('dev-primary', $tagA['lastSeenBy']['primaryDeviceId']);
        $this->assertSame('secondary', $tagA['lastReport']['role']);
    }

    public function testWorkerFallsBackToRelayPrimaryWhenTagHasNoShipmentBinding(): void
    {
        $this->shipments->seed('ship-7', ['boundDeviceIds' => ['dev-primary']]);

        $this->worker->handleDeviceReport(self::SECONDARY);

        $ship = $this->shipments->get('ship-7');
        $this->assertCount(1, $ship['events']);
        $this->assertSame('rpt-tag-A', $ship['events'][0]['_id']);
    }

    public function testWorkerAppendsToShipmentMatchedByDeviceName(): void
    {
        $this->shipments->seed('ship-42', ['boundDeviceNames' => ['SHIP-42']]);

        $payload = self::PRIMARY;
        $payload['id'] = 'rpt-with-name';
        $payload['device']['name'] = 'SHIP-42';

        $this->worker->handleDeviceReport($payload);

        $ship = $this->shipments->get('ship-42');
        $this->assertCount(1, $ship['events']);
    }

    public function testWorkerIsNullSafeForMinimalReport(): void
    {
        $this->worker->handleDeviceReport([
            'id' => 'rpt-empty',
            'time' => '2026-04-09T10:00:00.000Z',
            'device' => [
                'id' => 'dev-empty', 'displayId' => 'X',
                'model' => ['family' => 'locoTrack', 'name' => 'HGD4', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1],
                'labels' => [],
            ],
            'owner' => ['id' => 'own-1', 'name' => 'Acme'],
        ]);
        $stored = $this->deviceReports->get('rpt-empty');
        $this->assertSame('standalone', $stored['role']);
        $this->assertNull($this->devices->get('dev-empty')['lastSensors'] ?? null);
    }
}
