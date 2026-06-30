package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parse a Device Reports V2 webhook payload into a document-store-shaped record.
 *
 * <p>The output mirrors what you'd insert into MongoDB / DocumentDB / CosmosDB /
 * Couchbase — {@code _id} keyed to the report ID, with each documented
 * subsection extracted into a named field. Tables and schema migrations are
 * out of scope.
 *
 * <h2>Null handling</h2>
 * The feed OMITS null/absent fields entirely rather than sending them as
 * {@code null} (see {@code Device_Reports_V2_Integration_Guide.md} §10). This
 * parser keeps that convention: every optional field is only set on the
 * output document if the source field is present and non-null.
 *
 * <h2>Primary / Secondary reporting</h2>
 * LocoTrack hubs (primaries, a.k.a. "masters") relay BLE LocoTag (secondary,
 * a.k.a. "slave") state. The same tag emits TWO feed messages per reporting
 * window — one from the primary listing it in {@code secondaryObservations[]},
 * and one standalone from the tag with {@code reportedBy.device} pointing
 * back at the primary. Each document is tagged with a {@code role}:
 *
 * <ul>
 *   <li>{@code "primary"}    — this report came from a hub that observed secondaries</li>
 *   <li>{@code "secondary"}  — this report was relayed by a primary</li>
 *   <li>{@code "standalone"} — a self-contained device reporting itself</li>
 * </ul>
 *
 * The two messages link both ways:
 * <pre>
 *   primaryDoc.secondaries[i].reportId == secondaryDoc._id
 *   secondaryDoc.relayedBy.reportId    == primaryDoc._id
 * </pre>
 */
public final class DeviceReportParser {

    private DeviceReportParser() {}

    public static Map<String, Object> parse(JsonNode report) {
        if (report == null || report.isMissingNode() || report.isNull()) return null;

        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("_id", text(report, "id"));
        doc.put("time", text(report, "time"));
        doc.put("role", deriveRole(report));
        doc.put("device", parseDeviceIdentity(report.path("device")));
        doc.put("owner", parseOwner(report.path("owner")));
        doc.put("reportReasons", asStringList(report.path("reportReasons")));

        putIfPresent(doc, "rxTime", text(report, "rxTime"));

        Map<String, Object> relayedBy = parseRelayedBy(report.path("reportedBy"));
        if (relayedBy != null) doc.put("relayedBy", relayedBy);

        List<Map<String, Object>> secondaries = parseSecondaries(report.path("secondaryObservations"));
        if (!secondaries.isEmpty()) doc.put("secondaries", secondaries);

        List<Map<String, Object>> observedBy = parseObservedBy(report.path("observedBy"));
        if (!observedBy.isEmpty()) doc.put("observedBy", observedBy);

        List<Map<String, Object>> thirdParty = parseThirdParty(report.path("thirdPartyObservations"));
        if (!thirdParty.isEmpty()) doc.put("thirdPartyObservations", thirdParty);

        Map<String, Object> location = parseLocation(report.path("location"));
        if (location != null) doc.put("location", location);

        Map<String, Object> sensors = parseSensors(report.path("sensors"));
        if (sensors != null) doc.put("sensors", sensors);

        List<Object> sensorEvents = jsonNodeToList(report.path("sensorEvents"));
        if (!sensorEvents.isEmpty()) doc.put("sensorEvents", sensorEvents);

        Map<String, Object> networks = parseNetworkObservations(report.path("networkObservations"));
        if (networks != null) doc.put("networkObservations", networks);

        Map<String, Object> ts = parseTimeSeries(report.path("timeSeries"));
        if (ts != null) doc.put("timeSeries", ts);

        return doc;
    }

    /**
     * Compact per-device "latest state" projection — what you'd upsert into a
     * {@code devices} collection on every report. Full history lives in
     * {@code device_reports}.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> projectLatestState(Map<String, Object> doc) {
        if (doc == null) return null;
        Map<String, Object> device = (Map<String, Object>) doc.get("device");
        if (device == null) return null;

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("_id", device.get("id"));
        state.put("displayId", device.get("displayId"));
        state.put("model", device.get("model"));
        state.put("labels", device.getOrDefault("labels", List.of()));

        Map<String, Object> lastReport = new LinkedHashMap<>();
        lastReport.put("id", doc.get("_id"));
        lastReport.put("time", doc.get("time"));
        lastReport.put("role", doc.get("role"));
        state.put("lastReport", lastReport);
        state.put("updatedAt", doc.get("time"));

        if (device.get("name") != null) state.put("name", device.get("name"));
        if (device.get("firmware") != null) state.put("firmware", device.get("firmware"));
        if (doc.get("location") != null) state.put("lastLocation", doc.get("location"));
        if (doc.get("sensors") != null) state.put("lastSensors", doc.get("sensors"));
        if (doc.get("relayedBy") != null) state.put("relayedBy", doc.get("relayedBy"));
        return state;
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static String deriveRole(JsonNode report) {
        if (!report.path("reportedBy").path("device").path("id").isMissingNode()
                && !report.path("reportedBy").path("device").path("id").isNull()) {
            return "secondary";
        }
        JsonNode secondaries = report.path("secondaryObservations");
        if (secondaries.isArray() && secondaries.size() > 0) return "primary";
        return "standalone";
    }

    private static Map<String, Object> parseDeviceIdentity(JsonNode device) {
        if (device == null || device.isMissingNode() || device.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", text(device, "id"));
        out.put("displayId", text(device, "displayId"));
        out.put("model", parseModel(device.path("model")));
        out.put("labels", asStringList(device.path("labels")));
        putIfPresent(out, "name", text(device, "name"));             // nullable
        putIfPresent(out, "firmware", text(device, "firmware"));     // nullable
        return out;
    }

    private static Map<String, Object> parseModel(JsonNode model) {
        if (model == null || model.isMissingNode() || model.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(model, out, "name");
        copyIfPresent(model, out, "family");
        copyIfPresent(model, out, "product");
        copyIfPresent(model, out, "version");
        copyIfPresent(model, out, "revision");
        copyIfPresent(model, out, "variant");
        copyIfPresent(model, out, "thirdParty");
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> parseOwner(JsonNode owner) {
        if (owner == null || owner.isMissingNode() || owner.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", text(owner, "id"));
        out.put("name", text(owner, "name"));
        return out;
    }

    private static Map<String, Object> parseRelayedBy(JsonNode reportedBy) {
        if (reportedBy == null || reportedBy.isMissingNode() || reportedBy.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();

        Map<String, Object> relayDevice = parseDeviceIdentity(reportedBy.path("device"));
        if (relayDevice != null) out.put("device", relayDevice);

        JsonNode rep = reportedBy.path("report");
        if (rep.isObject() && !rep.isNull()) {
            out.put("reportId", text(rep, "id"));                    // nullable
            putIfPresent(out, "reportTime", text(rep, "time"));
        }
        if (reportedBy.path("app").isObject()) {
            out.put("app", jsonNodeToObject(reportedBy.path("app")));
        }
        if (reportedBy.path("rssi").isInt() || reportedBy.path("rssi").isLong()) {
            out.put("rssi", reportedBy.path("rssi").asInt());
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> parseLocation(JsonNode location) {
        if (location == null || location.isMissingNode() || location.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(location, out, "type");
        copyIfPresent(location, out, "time");
        copyIfPresent(location, out, "summary");

        JsonNode global = location.path("global");
        if (global.isObject() && global.size() > 0) {
            Map<String, Object> g = new LinkedHashMap<>();
            copyIfPresent(global, g, "lat");
            copyIfPresent(global, g, "lon");
            copyIfPresent(global, g, "cep");
            copyIfPresent(global, g, "address");
            if (!g.isEmpty()) out.put("global", g);
        }

        JsonNode site = location.path("site");
        if (site.isObject() && site.size() > 0) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (String key : List.of("id", "name", "level", "x", "y", "z", "address", "cep", "rooms")) {
                copyIfPresent(site, s, key);
            }
            if (!s.isEmpty()) out.put("site", s);
        }

        JsonNode zones = location.path("zones");
        if (zones.isArray() && zones.size() > 0) {
            List<Map<String, Object>> list = new ArrayList<>();
            for (JsonNode z : zones) {
                Map<String, Object> zone = new LinkedHashMap<>();
                copyIfPresent(z, zone, "id");
                copyIfPresent(z, zone, "name");
                if (!zone.isEmpty()) list.add(zone);
            }
            if (!list.isEmpty()) out.put("zones", list);
        }

        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> parseSensors(JsonNode sensors) {
        if (sensors == null || sensors.isMissingNode() || sensors.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(sensors, out, "time");

        Map<String, Object> battery = new LinkedHashMap<>();
        JsonNode bat = sensors.path("battery");
        if (bat.isObject()) {
            for (String key : List.of("level", "state", "voltage", "charging")) copyIfPresent(bat, battery, key);
            if (!battery.isEmpty()) out.put("battery", battery);
        }

        Map<String, Object> env = new LinkedHashMap<>();
        for (String key : List.of("temperature", "extremeTemperature", "humidity", "atmosphericPressure",
                                  "lightLevel", "movement", "externalPower", "cellSignal")) {
            copyIfPresent(sensors, env, key);
        }
        if (!env.isEmpty()) out.put("environment", env);

        Map<String, Object> orientation = parseVector(sensors.path("orientation"));
        if (orientation != null) out.put("orientation", orientation);
        Map<String, Object> vibration = parseVector(sensors.path("vibration"));
        if (vibration != null) out.put("vibration", vibration);

        JsonNode secSw = sensors.path("securitySwitch");
        if (secSw.isObject() && secSw.size() > 0) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (String key : List.of("state", "activations", "lastOpened", "lastClosed")) copyIfPresent(secSw, s, key);
            if (!s.isEmpty()) out.put("securitySwitch", s);
        }

        JsonNode ster = sensors.path("sterilisation");
        if (ster.isObject() && ster.size() > 0) {
            Map<String, Object> s = new LinkedHashMap<>();
            for (String key : List.of("cycleCount", "cycleCountReadAt", "uptimeInSeconds", "uptimeReadAt")) {
                copyIfPresent(ster, s, key);
            }
            if (!s.isEmpty()) out.put("sterilisation", s);
        }

        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> parseVector(JsonNode vec) {
        if (!vec.isObject() || vec.size() == 0) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        copyIfPresent(vec, out, "x");
        copyIfPresent(vec, out, "y");
        copyIfPresent(vec, out, "z");
        return out.isEmpty() ? null : out;
    }

    private static List<Map<String, Object>> parseSecondaries(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode obs : arr) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", text(obs, "id"));
            entry.put("displayId", text(obs, "displayId"));
            entry.put("model", parseModel(obs.path("model")));
            entry.put("reportType", text(obs, "reportType"));
            for (String key : List.of("name", "reportEventId", "reportId", "rssi", "isPaired")) {
                copyIfPresent(obs, entry, key);
            }
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> parseObservedBy(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode obs : arr) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", text(obs, "id"));
            entry.put("displayId", text(obs, "displayId"));
            entry.put("model", parseModel(obs.path("model")));
            for (String key : List.of("name", "reportId", "reportTime", "rssi")) copyIfPresent(obs, entry, key);
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> parseThirdParty(JsonNode arr) {
        List<Map<String, Object>> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode obs : arr) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", text(obs, "type"));
            entry.put("id", text(obs, "id"));
            entry.put("displayId", text(obs, "displayId"));
            String type = text(obs, "type");
            switch (type == null ? "" : type) {
                case "reelable" -> copyIfPresent(obs, entry, "temperature");
                case "chorus" -> {
                    for (String key : List.of("temperature", "sequenceNumber", "batteryLevel", "rssi", "tamper")) {
                        copyIfPresent(obs, entry, key);
                    }
                }
                case "cableSeal" -> {
                    for (String key : List.of("state", "open", "highTemperature", "lowBattery",
                                              "temperature", "batteryLevel", "counter", "rssi")) {
                        copyIfPresent(obs, entry, key);
                    }
                }
                default -> entry.put("raw", jsonNodeToObject(obs));   // forward-compat
            }
            out.add(entry);
        }
        return out;
    }

    private static Map<String, Object> parseNetworkObservations(JsonNode networks) {
        if (networks == null || networks.isMissingNode() || networks.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        for (String key : List.of("wifi", "cells", "lteNeighbourCells")) {
            JsonNode arr = networks.path(key);
            if (arr.isArray() && arr.size() > 0) out.put(key, jsonNodeToList(arr));
        }
        return out.isEmpty() ? null : out;
    }

    private static Map<String, Object> parseTimeSeries(JsonNode ts) {
        if (ts == null || ts.isMissingNode() || ts.isNull()) return null;
        Map<String, Object> out = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> it = ts.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (entry.getValue().isArray() && entry.getValue().size() > 0) {
                out.put(entry.getKey(), jsonNodeToList(entry.getValue()));
            }
        }
        return out.isEmpty() ? null : out;
    }

    // ── primitive helpers ──────────────────────────────────────────────────

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    private static void copyIfPresent(JsonNode src, Map<String, Object> dst, String field) {
        JsonNode v = src.path(field);
        if (v.isMissingNode() || v.isNull()) return;
        dst.put(field, jsonNodeToObject(v));
    }

    private static void putIfPresent(Map<String, Object> dst, String field, Object value) {
        if (value != null) dst.put(field, value);
    }

    private static List<String> asStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode el : arr) out.add(el.asText());
        return out;
    }

    private static List<Object> jsonNodeToList(JsonNode arr) {
        List<Object> out = new ArrayList<>();
        if (!arr.isArray()) return out;
        for (JsonNode el : arr) out.add(jsonNodeToObject(el));
        return out;
    }

    private static Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> map.put(e.getKey(), jsonNodeToObject(e.getValue())));
            return map;
        }
        if (node.isArray()) return jsonNodeToList(node);
        if (node.isBoolean()) return node.booleanValue();
        if (node.isInt()) return node.intValue();
        if (node.isLong()) return node.longValue();
        if (node.isFloatingPointNumber()) return node.doubleValue();
        return node.asText();
    }
}
