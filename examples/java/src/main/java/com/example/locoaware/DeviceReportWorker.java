package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Worker — drains messages the controller enqueued via {@link QueueClient}.
 *
 * <p>The in-process Spring event bus is for illustration only. In production
 * the equivalent listener is an {@code @SqsListener} / {@code @KafkaListener}
 * / {@code @RabbitListener} running in a separate process, so the web tier
 * can stay lean under high ingest load.
 *
 * <p>For each report, four writes happen against the document store:
 * <ol>
 *   <li>Insert the parsed document into the immutable {@code device_reports} history.</li>
 *   <li>If a shipment binds this device or its primary, append the document to it.</li>
 *   <li>Upsert the reporting device's "latest state" in {@code devices}.</li>
 *   <li>For primaries only: presence-touch each paired secondary so dashboards
 *       can show "last seen by primary at &lt;time&gt;" without waiting. Sensor
 *       data is NOT clobbered here — the tag's own report is authoritative.</li>
 * </ol>
 */
@Component
@EnableAsync
public class DeviceReportWorker {

    private static final Logger log = LoggerFactory.getLogger(DeviceReportWorker.class);

    private final Repositories.Shipments shipments;
    private final Repositories.Devices devices;
    private final Repositories.DeviceReports deviceReports;

    public DeviceReportWorker(Repositories.Shipments shipments,
                              Repositories.Devices devices,
                              Repositories.DeviceReports deviceReports) {
        this.shipments = shipments;
        this.devices = devices;
        this.deviceReports = deviceReports;
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

    @SuppressWarnings("unchecked")
    void handleDeviceReport(JsonNode report) {
        Map<String, Object> doc = DeviceReportParser.parse(report);
        if (doc == null) return;

        // 1. Immutable history.
        deviceReports.insert(doc);

        // 2. Shipment association — primary lookup, then relay fallback for tags.
        String shipmentId = findShipmentForReport(doc);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, doc);
        }

        // 3. Per-device latest state for the reporting device.
        String deviceId = deviceIdOf(doc);
        if (deviceId != null && devices.exists(deviceId)) {
            devices.upsertLatest(deviceId, DeviceReportParser.projectLatestState(doc));
        }

        // 4. Primary-side presence touches for paired secondaries.
        if ("primary".equals(doc.get("role"))) {
            Object secs = doc.get("secondaries");
            if (secs instanceof List<?> list) {
                for (Object obj : list) {
                    if (!(obj instanceof Map)) continue;
                    Map<String, Object> obs = (Map<String, Object>) obj;
                    String secondaryId = (String) obs.get("id");
                    if (secondaryId == null || !devices.exists(secondaryId)) continue;
                    devices.touchObservation(
                            secondaryId,
                            deviceId,
                            obs.get("rssi") instanceof Number n ? n.intValue() : null,   // nullable
                            (String) obs.get("reportType"),
                            (String) doc.get("time"));
                }
            }
        }
    }

    void handleDeviceEvent(JsonNode event) {
        Map<String, Object> doc = DeviceReportParser.parse(event);
        if (doc == null) return;

        String shipmentId = findShipmentForReport(doc);
        if (shipmentId != null) {
            shipments.appendEvent(shipmentId, doc);
        }

        String deviceId = deviceIdOf(doc);
        if (deviceId != null && devices.exists(deviceId)) {
            devices.upsertLatest(deviceId, DeviceReportParser.projectLatestState(doc));
        }
    }

    /**
     * Shipment lookup with a fallback to the relay/primary device. A
     * secondary's (BLE LocoTag) report carries the primary that relayed it
     * in {@code relayedBy.device}. Tags typically don't have a shipment
     * binding of their own — falling back to the primary attaches the tag's
     * data to the same shipment as the gateway it lives on.
     */
    @SuppressWarnings("unchecked")
    private String findShipmentForReport(Map<String, Object> doc) {
        Map<String, Object> device = (Map<String, Object>) doc.get("device");
        String deviceName = device == null ? null : (String) device.get("name");
        String deviceId   = device == null ? null : (String) device.get("id");

        String shipmentId = shipments.findIdByDeviceNameOrId(deviceName, deviceId);
        if (shipmentId != null) return shipmentId;

        Map<String, Object> relayedBy = (Map<String, Object>) doc.get("relayedBy");
        Map<String, Object> relayDevice = relayedBy == null ? null : (Map<String, Object>) relayedBy.get("device");
        if (relayDevice != null) {
            String relayName = (String) relayDevice.get("name");
            String relayId   = (String) relayDevice.get("id");
            if (relayName != null || relayId != null) {
                return shipments.findIdByDeviceNameOrId(relayName, relayId);
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String deviceIdOf(Map<String, Object> doc) {
        Map<String, Object> device = (Map<String, Object>) doc.get("device");
        return device == null ? null : (String) device.get("id");
    }
}
