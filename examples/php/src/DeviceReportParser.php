<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

/**
 * Parse a Device Reports V2 webhook payload into a document-store-shaped record.
 *
 * The output mirrors what you'd insert into MongoDB / DocumentDB / CosmosDB /
 * Couchbase — `_id` keyed to the report ID, with each documented subsection
 * extracted into a named field. Tables and schema migrations are out of scope.
 *
 * ─── Null handling ───────────────────────────────────────────────────────────
 * The feed OMITS null/absent fields entirely rather than sending them as
 * `null` (see Device_Reports_V2_Integration_Guide.md §10). This parser keeps
 * that convention: every optional field is only set on the output if the
 * source field is present and non-null. Optional outputs are flagged
 * "// nullable —" inline below.
 *
 * ─── Primary / Secondary reporting ───────────────────────────────────────────
 * LocoTrack hubs (primaries, a.k.a. "masters") relay BLE LocoTag (secondary,
 * a.k.a. "slave") state. The same tag emits TWO feed messages per reporting
 * window — one from the primary listing it in `secondaryObservations[]`, and
 * one standalone from the tag with `reportedBy.device` pointing back at the
 * primary. Each document is tagged with a `role`:
 *
 *   - "primary"    — this report came from a hub that observed secondaries
 *   - "secondary"  — this report was relayed by a primary
 *   - "standalone" — a self-contained device reporting itself
 *
 * The two messages link both ways:
 *   primaryDoc['secondaries'][i]['reportId'] == secondaryDoc['_id']
 *   secondaryDoc['relayedBy']['reportId']    == primaryDoc['_id']
 */
final class DeviceReportParser
{
    public static function parse(array $report): array
    {
        $role = self::deriveRole($report);

        $doc = [
            '_id' => $report['id'] ?? null,
            'time' => $report['time'] ?? null,
            'role' => $role,
            'device' => self::parseDeviceIdentity($report['device'] ?? null),
            'owner' => self::parseOwner($report['owner'] ?? null),
            'reportReasons' => $report['reportReasons'] ?? [],
        ];

        if (self::present($report['rxTime'] ?? null)) {
            $doc['rxTime'] = $report['rxTime'];     // nullable
        }

        if (($relayedBy = self::parseRelayedBy($report['reportedBy'] ?? null)) !== null) {
            $doc['relayedBy'] = $relayedBy;
        }

        $secondaries = self::parseSecondaries($report['secondaryObservations'] ?? null);
        if (!empty($secondaries)) {
            $doc['secondaries'] = $secondaries;
        }

        $observedBy = self::parseObservedBy($report['observedBy'] ?? null);
        if (!empty($observedBy)) {
            $doc['observedBy'] = $observedBy;
        }

        $thirdParty = self::parseThirdParty($report['thirdPartyObservations'] ?? null);
        if (!empty($thirdParty)) {
            $doc['thirdPartyObservations'] = $thirdParty;
        }

        if (($location = self::parseLocation($report['location'] ?? null)) !== null) {
            $doc['location'] = $location;
        }

        if (($sensors = self::parseSensors($report['sensors'] ?? null)) !== null) {
            $doc['sensors'] = $sensors;
        }

        if (is_array($report['sensorEvents'] ?? null) && !empty($report['sensorEvents'])) {
            $doc['sensorEvents'] = $report['sensorEvents'];
        }

        if (($networks = self::parseNetworkObservations($report['networkObservations'] ?? null)) !== null) {
            $doc['networkObservations'] = $networks;
        }

        if (($ts = self::parseTimeSeries($report['timeSeries'] ?? null)) !== null) {
            $doc['timeSeries'] = $ts;
        }

        return $doc;
    }

    /**
     * Compact per-device "latest state" projection — what you'd upsert into a
     * `devices` collection on every report. Full history lives in
     * `device_reports`.
     */
    public static function projectLatestState(array $doc): ?array
    {
        if (!isset($doc['device']) || !is_array($doc['device'])) {
            return null;
        }
        $device = $doc['device'];

        $state = [
            '_id' => $device['id'] ?? null,
            'displayId' => $device['displayId'] ?? null,
            'model' => $device['model'] ?? null,
            'labels' => $device['labels'] ?? [],
            'lastReport' => [
                'id' => $doc['_id'] ?? null,
                'time' => $doc['time'] ?? null,
                'role' => $doc['role'] ?? null,
            ],
            'updatedAt' => $doc['time'] ?? null,
        ];
        if (self::present($device['name'] ?? null)) {
            $state['name'] = $device['name'];
        }
        if (self::present($device['firmware'] ?? null)) {
            $state['firmware'] = $device['firmware'];
        }
        if (isset($doc['location'])) {
            $state['lastLocation'] = $doc['location'];
        }
        if (isset($doc['sensors'])) {
            $state['lastSensors'] = $doc['sensors'];
        }
        if (isset($doc['relayedBy'])) {
            $state['relayedBy'] = $doc['relayedBy'];
        }
        return $state;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static function deriveRole(array $report): string
    {
        $relayedId = $report['reportedBy']['device']['id'] ?? null;
        if (self::present($relayedId)) {
            return 'secondary';
        }
        $secondaries = $report['secondaryObservations'] ?? null;
        if (is_array($secondaries) && !empty($secondaries)) {
            return 'primary';
        }
        return 'standalone';
    }

    private static function parseDeviceIdentity(?array $device): ?array
    {
        if ($device === null) {
            return null;
        }
        $out = [
            'id' => $device['id'] ?? null,
            'displayId' => $device['displayId'] ?? null,
            'model' => self::parseModel($device['model'] ?? null),
            'labels' => $device['labels'] ?? [],
        ];
        if (self::present($device['name'] ?? null)) {
            $out['name'] = $device['name'];                  // nullable
        }
        if (self::present($device['firmware'] ?? null)) {
            $out['firmware'] = $device['firmware'];          // nullable
        }
        return $out;
    }

    private static function parseModel(?array $model): ?array
    {
        if ($model === null) {
            return null;
        }
        $out = self::pickPresent($model, ['name', 'family', 'product', 'version', 'revision', 'variant', 'thirdParty']);
        return empty($out) ? null : $out;
    }

    private static function parseOwner(?array $owner): ?array
    {
        if ($owner === null) {
            return null;
        }
        return ['id' => $owner['id'] ?? null, 'name' => $owner['name'] ?? null];
    }

    private static function parseRelayedBy(?array $reportedBy): ?array
    {
        if ($reportedBy === null) {
            return null;
        }
        $out = [];
        if (isset($reportedBy['device']) && is_array($reportedBy['device'])) {
            $out['device'] = self::parseDeviceIdentity($reportedBy['device']);
        }
        if (isset($reportedBy['report']) && is_array($reportedBy['report'])) {
            $out['reportId'] = $reportedBy['report']['id'] ?? null;   // nullable
            if (self::present($reportedBy['report']['time'] ?? null)) {
                $out['reportTime'] = $reportedBy['report']['time'];
            }
        }
        if (isset($reportedBy['app'])) {
            $out['app'] = $reportedBy['app'];                          // nullable
        }
        if (self::present($reportedBy['rssi'] ?? null)) {
            $out['rssi'] = $reportedBy['rssi'];                        // nullable
        }
        return empty($out) ? null : $out;
    }

    private static function parseLocation(?array $location): ?array
    {
        if ($location === null) {
            return null;
        }
        $out = self::pickPresent($location, ['type', 'time', 'summary']);

        if (isset($location['global']) && is_array($location['global'])) {
            $g = self::pickPresent($location['global'], ['lat', 'lon', 'cep', 'address']);
            if (!empty($g)) {
                $out['global'] = $g;
            }
        }
        if (isset($location['site']) && is_array($location['site'])) {
            $s = self::pickPresent($location['site'], ['id', 'name', 'level', 'x', 'y', 'z', 'address', 'cep', 'rooms']);
            if (!empty($s)) {
                $out['site'] = $s;
            }
        }
        if (isset($location['zones']) && is_array($location['zones']) && !empty($location['zones'])) {
            $out['zones'] = array_map(static fn($z) => self::pickPresent($z, ['id', 'name']), $location['zones']);
        }
        return empty($out) ? null : $out;
    }

    private static function parseSensors(?array $sensors): ?array
    {
        if ($sensors === null) {
            return null;
        }
        $out = [];
        if (self::present($sensors['time'] ?? null)) {
            $out['time'] = $sensors['time'];
        }

        if (isset($sensors['battery']) && is_array($sensors['battery'])) {
            $battery = self::pickPresent($sensors['battery'], ['level', 'state', 'voltage', 'charging']);
            if (!empty($battery)) {
                $out['battery'] = $battery;
            }
        }

        $env = self::pickPresent($sensors, [
            'temperature', 'extremeTemperature', 'humidity', 'atmosphericPressure',
            'lightLevel', 'movement', 'externalPower', 'cellSignal',
        ]);
        if (!empty($env)) {
            $out['environment'] = $env;
        }

        if (isset($sensors['orientation']) && is_array($sensors['orientation'])) {
            $out['orientation'] = self::pickPresent($sensors['orientation'], ['x', 'y', 'z']);
        }
        if (isset($sensors['vibration']) && is_array($sensors['vibration'])) {
            $out['vibration'] = self::pickPresent($sensors['vibration'], ['x', 'y', 'z']);
        }

        if (isset($sensors['securitySwitch']) && is_array($sensors['securitySwitch'])) {
            $out['securitySwitch'] = self::pickPresent(
                $sensors['securitySwitch'],
                ['state', 'activations', 'lastOpened', 'lastClosed']
            );
        }
        if (isset($sensors['sterilisation']) && is_array($sensors['sterilisation'])) {
            $out['sterilisation'] = self::pickPresent(
                $sensors['sterilisation'],
                ['cycleCount', 'cycleCountReadAt', 'uptimeInSeconds', 'uptimeReadAt']
            );
        }

        return empty($out) ? null : $out;
    }

    private static function parseSecondaries(?array $arr): array
    {
        if ($arr === null) {
            return [];
        }
        return array_map(static function (array $obs): array {
            return array_merge(
                [
                    'id' => $obs['id'] ?? null,
                    'displayId' => $obs['displayId'] ?? null,
                    'model' => self::parseModel($obs['model'] ?? null),
                    'reportType' => $obs['reportType'] ?? null,
                ],
                self::pickPresent($obs, ['name', 'reportEventId', 'reportId', 'rssi', 'isPaired'])
            );
        }, $arr);
    }

    private static function parseObservedBy(?array $arr): array
    {
        if ($arr === null) {
            return [];
        }
        return array_map(static function (array $obs): array {
            return array_merge(
                [
                    'id' => $obs['id'] ?? null,
                    'displayId' => $obs['displayId'] ?? null,
                    'model' => self::parseModel($obs['model'] ?? null),
                ],
                self::pickPresent($obs, ['name', 'reportId', 'reportTime', 'rssi'])
            );
        }, $arr);
    }

    private static function parseThirdParty(?array $arr): array
    {
        if ($arr === null) {
            return [];
        }
        return array_map(static function (array $obs): array {
            $base = [
                'type' => $obs['type'] ?? null,
                'id' => $obs['id'] ?? null,
                'displayId' => $obs['displayId'] ?? null,
            ];
            switch ($obs['type'] ?? null) {
                case 'reelable':
                    return array_merge($base, self::pickPresent($obs, ['temperature']));
                case 'chorus':
                    return array_merge($base, self::pickPresent($obs, ['temperature', 'sequenceNumber', 'batteryLevel', 'rssi', 'tamper']));
                case 'cableSeal':
                    return array_merge($base, self::pickPresent($obs, [
                        'state', 'open', 'highTemperature', 'lowBattery',
                        'temperature', 'batteryLevel', 'counter', 'rssi',
                    ]));
                default:
                    // Forward-compat: round-trip unknown beacon types under 'raw'.
                    return array_merge($base, ['raw' => $obs]);
            }
        }, $arr);
    }

    private static function parseNetworkObservations(?array $networks): ?array
    {
        if ($networks === null) {
            return null;
        }
        $out = [];
        foreach (['wifi', 'cells', 'lteNeighbourCells'] as $key) {
            if (isset($networks[$key]) && is_array($networks[$key]) && !empty($networks[$key])) {
                $out[$key] = $networks[$key];
            }
        }
        return empty($out) ? null : $out;
    }

    private static function parseTimeSeries(?array $ts): ?array
    {
        if ($ts === null) {
            return null;
        }
        $out = [];
        foreach ($ts as $key => $series) {
            if (is_array($series) && !empty($series)) {
                $out[$key] = $series;
            }
        }
        return empty($out) ? null : $out;
    }

    private static function present(mixed $value): bool
    {
        return $value !== null;
    }

    private static function pickPresent(array $src, array $keys): array
    {
        $out = [];
        foreach ($keys as $key) {
            if (array_key_exists($key, $src) && $src[$key] !== null) {
                $out[$key] = $src[$key];
            }
        }
        return $out;
    }
}
