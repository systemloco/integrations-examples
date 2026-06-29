package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class WebhookController {

    private final QueueClient queue;
    private final Repositories.Shipments shipments;
    private final Repositories.Devices devices;
    private final Repositories.Assets assets;

    public WebhookController(
            QueueClient queue,
            Repositories.Shipments shipments,
            Repositories.Devices devices,
            Repositories.Assets assets) {
        this.queue = queue;
        this.shipments = shipments;
        this.devices = devices;
        this.assets = assets;
    }

    // ========================================================================
    // Shipments — low volume. Handle inline.
    // ========================================================================
    @PostMapping("/webhooks/shipments")
    public ResponseEntity<Void> onShipmentEvent(@RequestBody JsonNode event) {
        String shipmentId = event.path("shipment").path("id").asText(null);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, event);
        }

        // `report` events embed a device report under `payload`. Parse it and
        // upsert the per-device "latest state" record with a structured
        // projection rather than the raw envelope.
        if ("report".equals(event.path("type").asText()) && event.has("payload")) {
            Map<String, Object> doc = DeviceReportParser.parse(event.path("payload"));
            if (doc != null) {
                @SuppressWarnings("unchecked")
                Map<String, Object> device = (Map<String, Object>) doc.get("device");
                String deviceId = device == null ? null : (String) device.get("id");
                if (deviceId != null && devices.exists(deviceId)) {
                    devices.upsertLatest(deviceId, DeviceReportParser.projectLatestState(doc));
                }
            }
        }

        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // Assets — low volume. Handle inline.
    // ========================================================================
    @PostMapping("/webhooks/assets")
    public ResponseEntity<Void> onAssetEvent(@RequestBody JsonNode event) {
        String assetName = event.path("asset").path("name").asText(null);
        String assetId   = event.path("asset").path("id").asText(null);

        String shipmentId = shipments.findIdByAssetNameOrId(assetName, assetId);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, event);
        }

        if (assetId != null && assets.exists(assetId)) {
            assets.updateLatest(assetId, event);
        }

        return ResponseEntity.ok().build();
    }

    // ========================================================================
    // Device Events — medium volume, bursty. Enqueue.
    // ========================================================================
    @PostMapping("/webhooks/device-events")
    public ResponseEntity<Void> onDeviceEvent(@RequestBody JsonNode event) {
        // Do NOT process inline — a zone change across a large fleet can
        // produce dozens of events per second. Enqueue and ack.
        queue.publish("locoaware.device-events", event);
        return ResponseEntity.accepted().build();
    }

    // ========================================================================
    // Device Reports V2 — highest volume. Enqueue.
    // ========================================================================
    @PostMapping("/webhooks/device-reports")
    public ResponseEntity<Void> onDeviceReport(@RequestBody JsonNode report) {
        // Firehose — a single company can push hundreds of reports per second.
        // Don't even inspect the payload here beyond what you need to route it.
        queue.publish("locoaware.device-reports", report);
        return ResponseEntity.accepted().build();
    }
}
