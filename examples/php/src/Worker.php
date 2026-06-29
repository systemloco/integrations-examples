<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

/**
 * Worker — drains messages the Slim app enqueued via {@see QueueClient}.
 *
 * The {@see QueueClient} stand-in is for illustration only — in production
 * this lives in a separate long-running process (Symfony Messenger consumer,
 * a Laravel queue worker, a custom daemon, ...) subscribed to your real
 * broker (SQS, Service Bus, Pub/Sub, RabbitMQ, ...).
 *
 * For each device report, four writes happen against the document store:
 *   1. Insert the parsed document into the immutable `device_reports` history.
 *   2. If a shipment binds this device or its primary, append the document to it.
 *   3. Upsert the reporting device's "latest state" in `devices`.
 *   4. For primaries only: presence-touch each paired secondary. The tag's
 *      own report is authoritative for its sensors — that touch only updates
 *      `lastSeenBy`.
 */
final class Worker
{
    public function __construct(
        private readonly DeviceReports $deviceReports,
        private readonly Shipments $shipments,
        private readonly Devices $devices,
    ) {}

    public function handleDeviceReport(array $report): void
    {
        $doc = DeviceReportParser::parse($report);

        // 1. Immutable history.
        $this->deviceReports->insert($doc);

        // 2. Shipment association — primary lookup, then relay fallback for tags.
        if (($shipmentId = $this->findShipmentForReport($doc)) !== null) {
            $this->shipments->appendEvent($shipmentId, $doc);
        }

        // 3. Per-device latest state.
        $deviceId = $doc['device']['id'] ?? null;
        if ($deviceId !== null && $this->devices->exists($deviceId)) {
            $state = DeviceReportParser::projectLatestState($doc);
            if ($state !== null) {
                $this->devices->upsertLatest($deviceId, $state);
            }
        }

        // 4. Primary-side presence touches for paired secondaries.
        if (($doc['role'] ?? null) === 'primary' && is_array($doc['secondaries'] ?? null)) {
            foreach ($doc['secondaries'] as $obs) {
                $secondaryId = $obs['id'] ?? null;
                if (!is_string($secondaryId) || $secondaryId === '' || !$this->devices->exists($secondaryId)) {
                    continue;
                }
                $rssi = $obs['rssi'] ?? null;       // nullable
                $this->devices->touchObservation(
                    $secondaryId,
                    $deviceId,
                    is_int($rssi) ? $rssi : null,
                    $obs['reportType'] ?? null,
                    $doc['time'] ?? null,
                );
            }
        }
    }

    public function handleDeviceEvent(array $event): void
    {
        $doc = DeviceReportParser::parse($event);

        if (($shipmentId = $this->findShipmentForReport($doc)) !== null) {
            $this->shipments->appendEvent($shipmentId, $doc);
        }

        $deviceId = $doc['device']['id'] ?? null;
        if ($deviceId !== null && $this->devices->exists($deviceId)) {
            $state = DeviceReportParser::projectLatestState($doc);
            if ($state !== null) {
                $this->devices->upsertLatest($deviceId, $state);
            }
        }
    }

    /**
     * Shipment lookup with a fallback to the relay/primary device. A
     * secondary's (BLE LocoTag) report carries the primary that relayed it
     * in `relayedBy.device`. Tags typically don't have a shipment binding of
     * their own — falling back to the primary attaches the tag's data to
     * the same shipment as the gateway it lives on.
     */
    private function findShipmentForReport(array $doc): ?string
    {
        $deviceName = $doc['device']['name'] ?? null;
        $deviceId   = $doc['device']['id']   ?? null;
        if (($shipmentId = $this->shipments->findIdByDeviceNameOrId($deviceName, $deviceId)) !== null) {
            return $shipmentId;
        }

        $relay = $doc['relayedBy']['device'] ?? null;
        if (is_array($relay)) {
            $relayName = $relay['name'] ?? null;
            $relayId   = $relay['id']   ?? null;
            if ($relayName !== null || $relayId !== null) {
                return $this->shipments->findIdByDeviceNameOrId($relayName, $relayId);
            }
        }
        return null;
    }
}
