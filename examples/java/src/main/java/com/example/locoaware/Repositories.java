package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Stand-in repositories. TODO: replace with your real persistence layer —
 * Spring Data JPA, JDBC, MongoDB, etc.
 */
public class Repositories {

    private static final Logger log = LoggerFactory.getLogger(Repositories.class);

    @Component
    public static class Shipments {
        public void appendEvent(String shipmentId, JsonNode event) {
            log.info("append event {} → shipment {}",
                    event.path("id").asText("(shipment message)"), shipmentId);
        }

        /**
         * Return the ID of the shipment associated with EITHER the device's
         * name (which may hold a shipment ID or name) OR the device's id
         * (if the device is assigned to an active shipment).
         *
         * Real implementation — a single indexed SQL statement, cached:
         * <pre>
         *   SELECT id FROM shipments
         *   WHERE status IN ('ready', 'inProgress')
         *     AND (name = :name OR id = :name OR id IN (
         *       SELECT shipment_id FROM shipment_devices WHERE device_id = :id
         *     ))
         *   LIMIT 1;
         * </pre>
         * Cache this aggressively — devices emit reports in bursts.
         */
        public String findIdByDeviceNameOrId(String deviceName, String deviceId) {
            log.info("lookup shipment by deviceName={} OR deviceId={}", deviceName, deviceId);
            return null;
        }

        public String findIdByAssetNameOrId(String assetName, String assetId) {
            log.info("lookup shipment by assetName={} OR assetId={}", assetName, assetId);
            return null;
        }
    }

    @Component
    public static class Devices {
        public boolean exists(String deviceId) { return deviceId != null; }
        public void updateLatest(String deviceId, JsonNode event) {
            log.info("update latest for device {}", deviceId);
        }
    }

    @Component
    public static class Assets {
        public boolean exists(String assetId) { return assetId != null; }
        public void updateLatest(String assetId, JsonNode event) {
            log.info("update latest for asset {}", assetId);
        }
    }
}
