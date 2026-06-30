<?php

declare(strict_types=1);

namespace LocoAware\Webhook\Tests;

use LocoAware\Webhook\Assets;
use LocoAware\Webhook\Devices;
use LocoAware\Webhook\QueueClient;
use LocoAware\Webhook\Shipments;

class RecordingQueueClient extends QueueClient
{
    /** @var array<int, array{topic:string, body:string}> */
    public array $published = [];

    public function publish(string $topic, string $rawBody): void
    {
        $this->published[] = ['topic' => $topic, 'body' => $rawBody];
    }
}

class RecordingShipments extends Shipments
{
    /** @var array<int, array{shipmentId:string, event:array}> */
    public array $appended = [];
    /** @var array<int, array{0:?string, 1:?string}> */
    public array $deviceLookups = [];
    /** @var array<int, array{0:?string, 1:?string}> */
    public array $assetLookups = [];

    public function appendEvent(string $shipmentId, array $event): void
    {
        $this->appended[] = ['shipmentId' => $shipmentId, 'event' => $event];
    }

    public function findIdByDeviceNameOrId(?string $deviceName, ?string $deviceId): ?string
    {
        $this->deviceLookups[] = [$deviceName, $deviceId];
        return null;
    }

    public function findIdByAssetNameOrId(?string $assetName, ?string $assetId): ?string
    {
        $this->assetLookups[] = [$assetName, $assetId];
        return null;
    }
}

class RecordingDevices extends Devices
{
    /** @var string[] */
    public array $updates = [];

    public function updateLatest(string $deviceId, array $event): void
    {
        $this->updates[] = $deviceId;
    }

    public function upsertLatest(string $deviceId, array $patch): void
    {
        $this->updates[] = $deviceId;
    }
}

class RecordingAssets extends Assets
{
    /** @var string[] */
    public array $updates = [];

    public function updateLatest(string $assetId, array $event): void
    {
        $this->updates[] = $assetId;
    }
}
