package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

/**
 * Worker — drains messages the controller enqueued via {@link QueueClient}.
 *
 * In this example the "queue" is Spring's in-process event bus; in production
 * the equivalent listener is an {@code @SqsListener} / {@code @KafkaListener}
 * / {@code @RabbitListener} running in a separate process, so the web tier
 * can stay lean under high ingest load.
 */
@Component
@EnableAsync
public class DeviceReportWorker {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportWorker.class);

    private final Repositories.Shipments shipments;
    private final Repositories.Devices devices;

    public DeviceReportWorker(Repositories.Shipments shipments, Repositories.Devices devices) {
        this.shipments = shipments;
        this.devices = devices;
    }

    @Async
    @EventListener
    public void onMessage(QueueClient.QueueMessage message) {
        try {
            switch (message.topic()) {
                case "locoaware.device-reports" -> handleDeviceReport(message.body());
                case "locoaware.device-events"  -> handleDeviceEvent(message.body());
                default -> log.warn("ignoring message on unknown topic {}", message.topic());
            }
        } catch (Exception e) {
            log.error("worker failed on {}; will rely on broker redelivery", message.topic(), e);
            throw e; // real broker: don't ack → message returns to the queue / DLQ
        }
    }

    private void handleDeviceReport(JsonNode report) {
        String deviceId = report.path("device").path("id").asText(null);

        String shipmentId = findShipmentForReport(report);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, report);
        }

        if (deviceId != null && devices.exists(deviceId)) {
            devices.updateLatest(deviceId, report);
        }

        // Touch each observed secondary (paired tag) — presence only. The tag's
        // authoritative sensor data arrives in its own separate report.
        for (JsonNode observation : report.path("secondaryObservations")) {
            String secondaryId = observation.path("id").asText(null);
            if (secondaryId != null && devices.exists(secondaryId)) {
                devices.updateLatest(secondaryId, observation);
            }
        }
    }

    private void handleDeviceEvent(JsonNode event) {
        String deviceId = event.path("device").path("id").asText(null);

        String shipmentId = findShipmentForReport(event);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, event);
        }

        if (deviceId != null && devices.exists(deviceId)) {
            devices.updateLatest(deviceId, event);
        }
    }

    /**
     * Shipment lookup with a fallback to the relay/primary device.
     *
     * A secondary's (BLE tag's) report carries the primary that relayed it in
     * {@code reportedBy.device}. Tags typically don't have a shipment binding
     * of their own — falling back to the primary attaches the tag's data to
     * the same shipment as the gateway it lives on.
     */
    private String findShipmentForReport(JsonNode report) {
        String deviceName = report.path("device").path("name").asText(null);
        String deviceId   = report.path("device").path("id").asText(null);
        String shipmentId = shipments.findIdByDeviceNameOrId(deviceName, deviceId);
        if (shipmentId != null) {
            return shipmentId;
        }

        JsonNode relay = report.path("reportedBy").path("device");
        String relayName = relay.path("name").asText(null);
        String relayId   = relay.path("id").asText(null);
        if (relayName != null || relayId != null) {
            return shipments.findIdByDeviceNameOrId(relayName, relayId);
        }
        return null;
    }
}
