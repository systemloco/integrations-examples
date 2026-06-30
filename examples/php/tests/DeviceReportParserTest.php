<?php

declare(strict_types=1);

namespace LocoAware\Webhook\Tests;

use LocoAware\Webhook\DeviceReportParser;
use PHPUnit\Framework\TestCase;

class DeviceReportParserTest extends TestCase
{
    private const PRIMARY = [
        'id' => 'rpt-primary-1',
        'time' => '2026-04-09T10:47:58.612Z',
        'rxTime' => '2026-04-09T10:47:58.716Z',
        'device' => [
            'id' => 'dev-primary', 'displayId' => 'HGD-1',
            'model' => ['name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1],
            'labels' => ['cold-chain'],
        ],
        'owner' => ['id' => 'own-1', 'name' => 'Acme'],
        'reportReasons' => ['report'],
        'location' => ['type' => 'wifi', 'global' => ['lat' => 1.0, 'lon' => 2.0]],
        'sensors' => [
            'battery' => ['level' => 100, 'state' => 'good'],
            'temperature' => 25.1,
            'orientation' => ['x' => 0.0, 'y' => 0.0, 'z' => -1.0],
        ],
        'secondaryObservations' => [
            [
                'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
                'model' => ['name' => 'E1BL', 'family' => 'locoCard', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0],
                'reportType' => 'presence', 'reportId' => 'rpt-tag-A', 'rssi' => -53, 'isPaired' => true,
            ],
        ],
        'thirdPartyObservations' => [
            [
                'type' => 'cableSeal', 'id' => 'A2:A2:A2:00:07:AB', 'displayId' => 'A2A2A20007AB',
                'state' => 'closed', 'open' => false, 'temperature' => 21, 'counter' => 7,
            ],
        ],
    ];

    private const SECONDARY = [
        'id' => 'rpt-tag-A',
        'time' => '2026-04-09T10:48:51.000Z',
        'device' => [
            'id' => 'dev-tag-A', 'displayId' => 'TAG-A',
            'model' => ['name' => 'E1BL', 'family' => 'locoCard', 'product' => 'LocoCard', 'version' => 1, 'revision' => 0],
            'labels' => [],
        ],
        'owner' => ['id' => 'own-1', 'name' => 'Acme'],
        'reportedBy' => [
            'device' => [
                'id' => 'dev-primary', 'displayId' => 'HGD-1',
                'model' => ['name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1],
            ],
            'report' => ['id' => 'rpt-primary-1', 'time' => '2026-04-09T10:47:58.612Z'],
            'rssi' => -53,
        ],
        'sensors' => ['battery' => ['level' => 80]],
        'timeSeries' => ['temperature' => [['time' => '2026-04-09T10:36:00.000Z', 'value' => 22.6]]],
    ];

    private const SPARSE = [
        'id' => 'rpt-sparse',
        'time' => '2026-04-09T10:00:00.000Z',
        'device' => [
            'id' => 'dev-sparse', 'displayId' => 'DS-1',
            'model' => ['name' => 'HGD4', 'family' => 'locoTrack', 'product' => 'LocoTrack', 'version' => 4, 'revision' => 1],
            'labels' => [],
        ],
        'owner' => ['id' => 'own-1', 'name' => 'Owner'],
    ];

    public function testPrimaryReportIsTaggedRolePrimary(): void
    {
        $doc = DeviceReportParser::parse(self::PRIMARY);
        $this->assertSame('primary', $doc['role']);
        $this->assertCount(1, $doc['secondaries']);
        $this->assertSame('rpt-tag-A', $doc['secondaries'][0]['reportId']);
        $this->assertTrue($doc['secondaries'][0]['isPaired']);
    }

    public function testSecondaryReportIsTaggedRoleSecondary(): void
    {
        $doc = DeviceReportParser::parse(self::SECONDARY);
        $this->assertSame('secondary', $doc['role']);
        $this->assertSame('dev-primary', $doc['relayedBy']['device']['id']);
        $this->assertSame('rpt-primary-1', $doc['relayedBy']['reportId']);
        $this->assertSame(-53, $doc['relayedBy']['rssi']);
    }

    public function testPrimaryToSecondaryLinkCanBeTracedBothWays(): void
    {
        $primary = DeviceReportParser::parse(self::PRIMARY);
        $secondary = DeviceReportParser::parse(self::SECONDARY);

        $matching = null;
        foreach ($primary['secondaries'] as $s) {
            if ($s['reportId'] === $secondary['_id']) {
                $matching = $s;
                break;
            }
        }
        $this->assertNotNull($matching);
        $this->assertSame($secondary['device']['id'], $matching['id']);

        $this->assertSame($primary['_id'], $secondary['relayedBy']['reportId']);
    }

    public function testStandaloneReportHasNoSecondariesOrRelayedBy(): void
    {
        $doc = DeviceReportParser::parse(self::SPARSE);
        $this->assertSame('standalone', $doc['role']);
        $this->assertArrayNotHasKey('secondaries', $doc);
        $this->assertArrayNotHasKey('relayedBy', $doc);
    }

    public function testSparseReportOmitsAbsentFields(): void
    {
        $doc = DeviceReportParser::parse(self::SPARSE);
        foreach (['rxTime', 'location', 'sensors', 'relayedBy', 'thirdPartyObservations', 'timeSeries'] as $k) {
            $this->assertArrayNotHasKey($k, $doc, "expected key '{$k}' to be absent on a sparse report");
        }
        $this->assertSame('rpt-sparse', $doc['_id']);
    }

    public function testSensorsAreGroupedIntoBatteryEnvironmentAndVectors(): void
    {
        $doc = DeviceReportParser::parse(self::PRIMARY);
        $this->assertSame(100, $doc['sensors']['battery']['level']);
        $this->assertSame(25.1, $doc['sensors']['environment']['temperature']);
        $this->assertSame(['x' => 0.0, 'y' => 0.0, 'z' => -1.0], $doc['sensors']['orientation']);
    }

    public function testThirdPartyObservationKeepsTypeSpecificFields(): void
    {
        $doc = DeviceReportParser::parse(self::PRIMARY);
        $seal = $doc['thirdPartyObservations'][0];
        $this->assertSame('cableSeal', $seal['type']);
        $this->assertSame('closed', $seal['state']);
        $this->assertSame(21, $seal['temperature']);
        $this->assertSame(7, $seal['counter']);
    }

    public function testUnknownThirdPartyTypeFallsBackToRaw(): void
    {
        $payload = array_merge(self::SPARSE, [
            'thirdPartyObservations' => [
                ['type' => 'futureBeacon', 'id' => 'F1', 'displayId' => 'F1', 'mystery' => true],
            ],
        ]);
        $beacon = DeviceReportParser::parse($payload)['thirdPartyObservations'][0];
        $this->assertSame('futureBeacon', $beacon['type']);
        $this->assertTrue($beacon['raw']['mystery']);
    }

    public function testTimeSeriesPassesThroughAndDropsEmptySeries(): void
    {
        $doc = DeviceReportParser::parse(self::SECONDARY);
        $this->assertCount(1, $doc['timeSeries']['temperature']);

        $empty = DeviceReportParser::parse(array_merge(self::SPARSE, ['timeSeries' => ['humidity' => []]]));
        $this->assertArrayNotHasKey('timeSeries', $empty);
    }

    public function testProjectLatestStateReturnsPerDeviceSnapshot(): void
    {
        $doc = DeviceReportParser::parse(self::PRIMARY);
        $state = DeviceReportParser::projectLatestState($doc);
        $this->assertSame($doc['device']['id'], $state['_id']);
        $this->assertSame($doc['_id'], $state['lastReport']['id']);
        $this->assertSame('primary', $state['lastReport']['role']);
        $this->assertArrayHasKey('lastSensors', $state);
        $this->assertArrayHasKey('lastLocation', $state);
    }

    public function testProjectLatestStateOmitsAbsentOptionals(): void
    {
        $doc = DeviceReportParser::parse(self::SPARSE);
        $state = DeviceReportParser::projectLatestState($doc);
        $this->assertSame('dev-sparse', $state['_id']);
        $this->assertArrayNotHasKey('lastLocation', $state);
        $this->assertArrayNotHasKey('lastSensors', $state);
        $this->assertArrayNotHasKey('relayedBy', $state);
    }
}
