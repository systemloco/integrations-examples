<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

/**
 * Stand-in repositories. TODO: replace with PDO / Doctrine / Eloquent.
 */
class Shipments
{
    public function appendEvent(string $shipmentId, array $event): void
    {
        $id = $event['id'] ?? '(shipment message)';
        error_log("[repo] append event {$id} → shipment {$shipmentId}");
    }

    /**
     * Return the shipment ID associated with EITHER the device's name (which
     * may carry a shipment ID or name) OR the device's id (if the device is
     * assigned to an active shipment).
     *
     * Real implementation — one indexed SQL statement, cached:
     *
     *   SELECT id FROM shipments
     *   WHERE status IN ('ready', 'inProgress')
     *     AND (name = :name OR id = :name OR id IN (
     *       SELECT shipment_id FROM shipment_devices WHERE device_id = :id
     *     ))
     *   LIMIT 1;
     *
     * Cache aggressively — devices emit reports in rapid bursts.
     */
    public function findIdByDeviceNameOrId(?string $deviceName, ?string $deviceId): ?string
    {
        error_log("[repo] lookup shipment by deviceName={$deviceName} OR deviceId={$deviceId}");
        return null;
    }

    public function findIdByAssetNameOrId(?string $assetName, ?string $assetId): ?string
    {
        error_log("[repo] lookup shipment by assetName={$assetName} OR assetId={$assetId}");
        return null;
    }
}

class Devices
{
    public function exists(string $deviceId): bool { return $deviceId !== ''; }

    public function updateLatest(string $deviceId, array $event): void
    {
        error_log("[repo] update latest for device {$deviceId}");
    }
}

class Assets
{
    public function exists(string $assetId): bool { return $assetId !== ''; }

    public function updateLatest(string $assetId, array $event): void
    {
        error_log("[repo] update latest for asset {$assetId}");
    }
}
