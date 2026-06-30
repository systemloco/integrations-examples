<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

/**
 * In-memory stand-ins for a document store. The shape mirrors what you'd
 * push into MongoDB / DocumentDB / CosmosDB / Firestore / Couchbase. The
 * point of the examples is to show the SHAPE of the documents — the actual
 * driver is one swap away.
 *
 * Production replacements:
 *   - MongoDB     mongodb/mongodb            $collection->updateOne(['_id' => $id], ['$set' => $doc], ['upsert' => true])
 *   - DocumentDB  mongodb/mongodb            (mongo-compatible — same call)
 *   - CosmosDB    via REST or Mongo API
 *   - DynamoDB    aws/aws-sdk-php            UpdateItem with upsert expression
 *   - Couchbase   couchbase/couchbase
 *
 * Tables and schema migrations are out of scope.
 */
class DeviceReports
{
    /** @var array<string, array<string, mixed>> */
    private array $store = [];

    public function insert(array $doc): void
    {
        if (!isset($doc['_id']) || $doc['_id'] === '') {
            return;
        }
        // In production: $collection->insertOne($doc).
        // _id from the report is reused so re-deliveries are idempotent.
        $this->store[$doc['_id']] = $doc;
        error_log('[repo] insert device_report ' . $doc['_id'] . ' (role=' . ($doc['role'] ?? 'unknown') . ')');
    }

    public function get(string $id): ?array
    {
        return $this->store[$id] ?? null;
    }

    /** @return array<int, array<string, mixed>> */
    public function all(): array
    {
        return array_values($this->store);
    }

    public function clear(): void
    {
        $this->store = [];
    }
}

class Shipments
{
    /** @var array<string, array<string, mixed>> */
    private array $store = [];

    public function appendEvent(string $shipmentId, array $event): void
    {
        if ($shipmentId === '') {
            return;
        }
        if (!isset($this->store[$shipmentId])) {
            $this->store[$shipmentId] = ['_id' => $shipmentId, 'events' => []];
        }
        $this->store[$shipmentId]['events'][] = $event;
        // In production:
        //   $collection->updateOne(
        //     ['_id' => $shipmentId],
        //     ['$push' => ['events' => $event]],
        //     ['upsert' => true]
        //   );
        $id = $event['_id'] ?? $event['id'] ?? '(message)';
        error_log("[repo] append event {$id} → shipment {$shipmentId}");
    }

    /**
     * Return the shipment ID associated with EITHER the device's name (which
     * may carry a shipment ID or name) OR the device's id (if the device is
     * assigned to an active shipment). The worker's hottest read — cache
     * aggressively in production.
     *
     * Real implementation — one indexed SQL statement:
     *   SELECT id FROM shipments
     *   WHERE status IN ('ready', 'inProgress')
     *     AND (name = :name OR id = :name OR id IN (
     *       SELECT shipment_id FROM shipment_devices WHERE device_id = :id
     *     ))
     *   LIMIT 1;
     */
    public function findIdByDeviceNameOrId(?string $deviceName, ?string $deviceId): ?string
    {
        error_log("[repo] lookup shipment by deviceName={$deviceName} OR deviceId={$deviceId}");
        foreach ($this->store as $id => $doc) {
            if ($deviceName !== null && in_array($deviceName, $doc['boundDeviceNames'] ?? [], true)) {
                return $id;
            }
            if ($deviceId !== null && in_array($deviceId, $doc['boundDeviceIds'] ?? [], true)) {
                return $id;
            }
        }
        return null;
    }

    public function findIdByAssetNameOrId(?string $assetName, ?string $assetId): ?string
    {
        error_log("[repo] lookup shipment by assetName={$assetName} OR assetId={$assetId}");
        foreach ($this->store as $id => $doc) {
            if ($assetName !== null && in_array($assetName, $doc['boundAssetNames'] ?? [], true)) {
                return $id;
            }
            if ($assetId !== null && in_array($assetId, $doc['boundAssetIds'] ?? [], true)) {
                return $id;
            }
        }
        return null;
    }

    public function seed(string $id, array $overlay): void
    {
        $this->store[$id] = array_merge(['_id' => $id, 'events' => []], $overlay);
    }

    public function get(string $id): ?array
    {
        return $this->store[$id] ?? null;
    }

    public function clear(): void
    {
        $this->store = [];
    }
}

class Devices
{
    /** @var array<string, array<string, mixed>> */
    private array $store = [];

    public function exists(string $deviceId): bool
    {
        // Real implementations might keep a known-device cache, or do
        // $collection->findOne(['_id' => $deviceId], ['projection' => ['_id' => 1]]).
        // For the stand-in we accept any non-empty id so the worker is exercised.
        return $deviceId !== '';
    }

    public function upsertLatest(string $deviceId, array $patch): void
    {
        if ($deviceId === '') {
            return;
        }
        $current = $this->store[$deviceId] ?? ['_id' => $deviceId];
        $this->store[$deviceId] = array_merge($current, $patch, ['_id' => $deviceId]);
        // In production:
        //   $collection->updateOne(
        //     ['_id' => $deviceId],
        //     ['$set' => $patch],
        //     ['upsert' => true]
        //   );
        error_log("[repo] upsert device {$deviceId}");
    }

    /**
     * Presence-only touch — a secondary's primary saw it during this
     * reporting window. The tag's AUTHORITATIVE sensor data lands in the
     * tag's own standalone report; do not overwrite sensor fields here.
     */
    public function touchObservation(string $secondaryId, ?string $primaryDeviceId, ?int $rssi, ?string $reportType, ?string $time): void
    {
        if ($secondaryId === '') {
            return;
        }
        $lastSeenBy = ['primaryDeviceId' => $primaryDeviceId];
        if ($rssi !== null) {
            $lastSeenBy['rssi'] = $rssi;            // nullable
        }
        if ($reportType !== null) {
            $lastSeenBy['reportType'] = $reportType;
        }
        if ($time !== null) {
            $lastSeenBy['time'] = $time;
        }

        $current = $this->store[$secondaryId] ?? ['_id' => $secondaryId];
        $current['_id'] = $secondaryId;
        $current['lastSeenBy'] = $lastSeenBy;
        $this->store[$secondaryId] = $current;
        error_log("[repo] touch device {$secondaryId} seen by primary " . ($primaryDeviceId ?? '(unknown)'));
    }

    /** Back-compat alias for callers handing in a raw envelope. */
    public function updateLatest(string $deviceId, array $event): void
    {
        $this->upsertLatest($deviceId, ['lastEvent' => $event]);
    }

    public function get(string $id): ?array
    {
        return $this->store[$id] ?? null;
    }

    public function clear(): void
    {
        $this->store = [];
    }
}

class Assets
{
    /** @var array<string, array<string, mixed>> */
    private array $store = [];

    public function exists(string $assetId): bool
    {
        return $assetId !== '';
    }

    public function updateLatest(string $assetId, array $event): void
    {
        if ($assetId === '') {
            return;
        }
        $this->store[$assetId] = ['_id' => $assetId, 'lastEvent' => $event];
        error_log("[repo] upsert asset {$assetId}");
    }

    public function get(string $id): ?array
    {
        return $this->store[$id] ?? null;
    }

    public function clear(): void
    {
        $this->store = [];
    }
}
