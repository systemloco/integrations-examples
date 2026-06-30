package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory stand-in for a document store. The shape mirrors what you'd push
 * into MongoDB / DocumentDB / CosmosDB / Firestore / Couchbase. The point of
 * the examples is to show the SHAPE of the documents — the actual driver is
 * one swap away.
 *
 * <p>Production replacements (typical Spring Boot wiring):
 * <ul>
 *   <li>MongoDB     {@code spring-boot-starter-data-mongodb} — {@code MongoTemplate.upsert(query, update, "devices")}</li>
 *   <li>CosmosDB    {@code azure-spring-data-cosmos}</li>
 *   <li>DynamoDB    {@code spring-cloud-aws-dynamodb}</li>
 *   <li>Couchbase   {@code spring-boot-starter-data-couchbase}</li>
 *   <li>Firestore   {@code spring-cloud-gcp-data-firestore}</li>
 * </ul>
 *
 * Tables and schema migrations are explicitly out of scope here.
 */
public class Repositories {

    private static final Logger log = LoggerFactory.getLogger(Repositories.class);

    // ─── Device reports — immutable history ─────────────────────────────────
    @Component
    public static class DeviceReports {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        public void insert(Map<String, Object> doc) {
            if (doc == null) return;
            Object id = doc.get("_id");
            if (id == null) return;
            // In production: collection.insertOne(doc) — keyed by report id so
            // re-deliveries are idempotent.
            store.put(id.toString(), doc);
            log.info("insert device_report {} (role={})", id, doc.get("role"));
        }

        public Map<String, Object> get(String id) { return store.get(id); }
        public List<Map<String, Object>> all() { return new ArrayList<>(store.values()); }
        public void clear() { store.clear(); }
    }

    // ─── Devices — per-device latest state ──────────────────────────────────
    @Component
    public static class Devices {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        public boolean exists(String deviceId) {
            // Real implementations might keep a known-device cache, or do
            // collection.findOne({_id: deviceId}, projection: {_id: 1}). For
            // the stand-in we accept any non-empty id so the worker is exercised.
            return deviceId != null && !deviceId.isEmpty();
        }

        public void upsertLatest(String deviceId, Map<String, Object> patch) {
            if (deviceId == null || patch == null) return;
            store.compute(deviceId, (k, existing) -> {
                Map<String, Object> next = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
                next.putAll(patch);
                next.put("_id", deviceId);
                return next;
            });
            // Production:
            //   mongo.upsert(
            //     Query.query(Criteria.where("_id").is(deviceId)),
            //     new Update().set(...patch...),
            //     "devices");
            log.info("upsert device {}", deviceId);
        }

        /**
         * Presence-only touch — a secondary's primary saw it during this
         * reporting window. The tag's AUTHORITATIVE sensor data lands in the
         * tag's own standalone report; do not overwrite sensor fields here.
         */
        public void touchObservation(String secondaryId,
                                     String primaryDeviceId,
                                     Integer rssi,
                                     String reportType,
                                     String time) {
            if (secondaryId == null) return;
            Map<String, Object> lastSeenBy = new LinkedHashMap<>();
            lastSeenBy.put("primaryDeviceId", primaryDeviceId);
            if (rssi != null) lastSeenBy.put("rssi", rssi);              // nullable
            if (reportType != null) lastSeenBy.put("reportType", reportType);
            if (time != null) lastSeenBy.put("time", time);

            store.compute(secondaryId, (k, existing) -> {
                Map<String, Object> next = existing == null ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
                next.put("_id", secondaryId);
                next.put("lastSeenBy", lastSeenBy);
                return next;
            });
            log.info("touch device {} seen by primary {}", secondaryId, primaryDeviceId);
        }

        /** Back-compat alias preserved for callers that hand in a JsonNode envelope. */
        public void updateLatest(String deviceId, JsonNode event) {
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("lastEvent", event);
            upsertLatest(deviceId, patch);
        }

        public Map<String, Object> get(String id) { return store.get(id); }
        public List<Map<String, Object>> all() { return new ArrayList<>(store.values()); }
        public void clear() { store.clear(); }
    }

    // ─── Shipments — events appended; lookup by bound device/asset ──────────
    @Component
    public static class Shipments {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        @SuppressWarnings("unchecked")
        public void appendEvent(String shipmentId, Object event) {
            if (shipmentId == null) return;
            store.compute(shipmentId, (k, existing) -> {
                Map<String, Object> doc = existing == null
                        ? newShipmentDoc(shipmentId) : new LinkedHashMap<>(existing);
                ((List<Object>) doc.get("events")).add(event);
                return doc;
            });
            log.info("append event → shipment {}", shipmentId);
        }

        /**
         * Return the ID of the shipment associated with EITHER the device's
         * name (which may hold a shipment ID or name) OR the device's id
         * (if the device is assigned to an active shipment). The worker's
         * hottest read — cache aggressively in production.
         *
         * <p>Real implementation — a single indexed SQL statement:
         * <pre>
         *   SELECT id FROM shipments
         *   WHERE status IN ('ready', 'inProgress')
         *     AND (name = :name OR id = :name OR id IN (
         *       SELECT shipment_id FROM shipment_devices WHERE device_id = :id
         *     ))
         *   LIMIT 1;
         * </pre>
         */
        public String findIdByDeviceNameOrId(String deviceName, String deviceId) {
            log.info("lookup shipment by deviceName={} OR deviceId={}", deviceName, deviceId);
            for (Map.Entry<String, Map<String, Object>> entry : store.entrySet()) {
                if (deviceName != null && asList(entry.getValue().get("boundDeviceNames")).contains(deviceName)) return entry.getKey();
                if (deviceId != null && asList(entry.getValue().get("boundDeviceIds")).contains(deviceId)) return entry.getKey();
            }
            return null;
        }

        public String findIdByAssetNameOrId(String assetName, String assetId) {
            log.info("lookup shipment by assetName={} OR assetId={}", assetName, assetId);
            for (Map.Entry<String, Map<String, Object>> entry : store.entrySet()) {
                if (assetName != null && asList(entry.getValue().get("boundAssetNames")).contains(assetName)) return entry.getKey();
                if (assetId != null && asList(entry.getValue().get("boundAssetIds")).contains(assetId)) return entry.getKey();
            }
            return null;
        }

        public void seed(String shipmentId, Map<String, Object> overlay) {
            Map<String, Object> doc = newShipmentDoc(shipmentId);
            doc.putAll(overlay);
            store.put(shipmentId, doc);
        }

        public Map<String, Object> get(String id) { return store.get(id); }
        public void clear() { store.clear(); }

        private static Map<String, Object> newShipmentDoc(String id) {
            Map<String, Object> doc = new LinkedHashMap<>();
            doc.put("_id", id);
            doc.put("events", new ArrayList<>());
            return doc;
        }

        @SuppressWarnings("unchecked")
        private static List<Object> asList(Object value) {
            if (value instanceof List) return (List<Object>) value;
            return List.of();
        }
    }

    // ─── Assets — per-asset latest state ────────────────────────────────────
    @Component
    public static class Assets {
        private final Map<String, Map<String, Object>> store = new ConcurrentHashMap<>();

        public boolean exists(String assetId) {
            return assetId != null && !assetId.isEmpty();
        }

        public void updateLatest(String assetId, JsonNode event) {
            if (assetId == null) return;
            Map<String, Object> doc = new HashMap<>();
            doc.put("_id", assetId);
            doc.put("lastEvent", event);
            store.put(assetId, doc);
            log.info("upsert asset {}", assetId);
        }

        public Map<String, Object> get(String id) { return store.get(id); }
        public void clear() { store.clear(); }
    }
}
