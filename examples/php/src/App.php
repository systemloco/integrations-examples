<?php

declare(strict_types=1);

namespace LocoAware\Webhook;

use Psr\Http\Message\ResponseInterface;
use Psr\Http\Message\ServerRequestInterface;
use Psr\Http\Server\RequestHandlerInterface;
use Slim\App;
use Slim\Factory\AppFactory;

/**
 * Construct the Slim app with the given collaborators.
 *
 * Pulled out of index.php so tests can pass recording doubles for the queue
 * and repositories without spinning up a real HTTP server.
 */
function createApp(
    QueueClient $queue,
    Shipments $shipments,
    Devices $devices,
    Assets $assets
): App {
    $app = AppFactory::create();

    // ============================================================================
    // HMAC signature middleware — runs on every /webhooks/* request.
    // ============================================================================
    $app->add(function (ServerRequestInterface $request, RequestHandlerInterface $handler): ResponseInterface {
        if (strpos($request->getUri()->getPath(), '/webhooks/') !== 0) {
            return $handler->handle($request);
        }

        $secret = getenv('LOCOAWARE_WEBHOOK_SECRET') ?: '';
        $header = getenv('LOCOAWARE_SIGNATURE_HEADER') ?: 'X-LocoAware-Signature';

        $rawBody = (string) $request->getBody();
        $request = $request->withAttribute('rawBody', $rawBody);

        if ($secret !== '') {
            $received = $request->getHeaderLine($header);
            // LocoAware sends base64(HMAC-SHA256(body, secret)).
            $expected = base64_encode(hash_hmac('sha256', $rawBody, $secret, true));
            if ($received === '' || !hash_equals($expected, $received)) {
                $response = (new \Slim\Psr7\Response())->withStatus(401);
                $response->getBody()->write('bad signature');
                return $response;
            }
        }

        return $handler->handle($request);
    });

    // ============================================================================
    // Shipments — low volume. Handle inline.
    // ============================================================================
    $app->post('/webhooks/shipments', function ($request, $response) use ($shipments, $devices) {
        $event = json_decode($request->getAttribute('rawBody'), true) ?? [];

        $shipmentId = $event['shipment']['id'] ?? null;
        if ($shipmentId !== null) {
            $shipments->appendEvent($shipmentId, $event);
        }

        if (($event['type'] ?? null) === 'report') {
            $deviceId = $event['payload']['device']['id'] ?? null;
            if ($deviceId !== null && $devices->exists($deviceId)) {
                $devices->updateLatest($deviceId, $event);
            }
        }

        return $response->withStatus(200);
    });

    // ============================================================================
    // Assets — low volume. Handle inline.
    // ============================================================================
    $app->post('/webhooks/assets', function ($request, $response) use ($shipments, $assets) {
        $event = json_decode($request->getAttribute('rawBody'), true) ?? [];

        $assetName = $event['asset']['name'] ?? null;
        $assetId   = $event['asset']['id'] ?? null;

        $shipmentId = $shipments->findIdByAssetNameOrId($assetName, $assetId);
        if ($shipmentId !== null) {
            $shipments->appendEvent($shipmentId, $event);
        }

        if ($assetId !== null && $assets->exists($assetId)) {
            $assets->updateLatest($assetId, $event);
        }

        return $response->withStatus(200);
    });

    // ============================================================================
    // Device Events — medium volume, bursty. Enqueue.
    // ============================================================================
    $app->post('/webhooks/device-events', function ($request, $response) use ($queue) {
        // Do NOT process inline — a zone change across a large fleet can produce
        // dozens of events per second.
        $queue->publish('locoaware.device-events', $request->getAttribute('rawBody'));
        return $response->withStatus(202);
    });

    // ============================================================================
    // Device Reports V2 — highest volume. Enqueue.
    // ============================================================================
    $app->post('/webhooks/device-reports', function ($request, $response) use ($queue) {
        // Firehose. Push to the broker and ack.
        $queue->publish('locoaware.device-reports', $request->getAttribute('rawBody'));
        return $response->withStatus(202);
    });

    return $app;
}
