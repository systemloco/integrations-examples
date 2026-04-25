<?php

declare(strict_types=1);

require_once __DIR__ . '/../vendor/autoload.php';
require_once __DIR__ . '/../src/QueueClient.php';
require_once __DIR__ . '/../src/Repositories.php';
require_once __DIR__ . '/../src/App.php';

use LocoAware\Webhook\Assets;
use LocoAware\Webhook\Devices;
use LocoAware\Webhook\QueueClient;
use LocoAware\Webhook\Shipments;
use function LocoAware\Webhook\createApp;

$app = createApp(
    new QueueClient(),
    new Shipments(),
    new Devices(),
    new Assets(),
);

$app->run();
