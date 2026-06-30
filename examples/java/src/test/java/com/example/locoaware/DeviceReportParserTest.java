package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceReportParserTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PRIMARY_JSON = "{"
            + "\"id\":\"rpt-primary-1\","
            + "\"time\":\"2026-04-09T10:47:58.612Z\","
            + "\"rxTime\":\"2026-04-09T10:47:58.716Z\","
            + "\"device\":{\"id\":\"dev-primary\",\"displayId\":\"HGD-1\","
            + "\"model\":{\"name\":\"HGD4\",\"family\":\"locoTrack\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1},"
            + "\"labels\":[\"cold-chain\"]},"
            + "\"owner\":{\"id\":\"own-1\",\"name\":\"Acme\"},"
            + "\"reportReasons\":[\"report\"],"
            + "\"location\":{\"type\":\"wifi\",\"global\":{\"lat\":1.0,\"lon\":2.0}},"
            + "\"sensors\":{\"battery\":{\"level\":100,\"state\":\"good\"},\"temperature\":25.1,"
            + "\"orientation\":{\"x\":0.0,\"y\":0.0,\"z\":-1.0}},"
            + "\"secondaryObservations\":[{\"id\":\"dev-tag-A\",\"displayId\":\"TAG-A\","
            + "\"model\":{\"name\":\"E1BL\",\"family\":\"locoCard\",\"product\":\"LocoCard\",\"version\":1,\"revision\":0},"
            + "\"reportType\":\"presence\",\"reportId\":\"rpt-tag-A\",\"rssi\":-53,\"isPaired\":true}],"
            + "\"thirdPartyObservations\":[{\"type\":\"cableSeal\",\"id\":\"A2:A2:A2:00:07:AB\",\"displayId\":\"A2A2A20007AB\","
            + "\"state\":\"closed\",\"open\":false,\"temperature\":21,\"counter\":7}]"
            + "}";

    private static final String SECONDARY_JSON = "{"
            + "\"id\":\"rpt-tag-A\","
            + "\"time\":\"2026-04-09T10:48:51.000Z\","
            + "\"device\":{\"id\":\"dev-tag-A\",\"displayId\":\"TAG-A\","
            + "\"model\":{\"name\":\"E1BL\",\"family\":\"locoCard\",\"product\":\"LocoCard\",\"version\":1,\"revision\":0},"
            + "\"labels\":[]},"
            + "\"owner\":{\"id\":\"own-1\",\"name\":\"Acme\"},"
            + "\"reportedBy\":{"
            + "\"device\":{\"id\":\"dev-primary\",\"displayId\":\"HGD-1\","
            + "\"model\":{\"name\":\"HGD4\",\"family\":\"locoTrack\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1}},"
            + "\"report\":{\"id\":\"rpt-primary-1\",\"time\":\"2026-04-09T10:47:58.612Z\"},"
            + "\"rssi\":-53},"
            + "\"sensors\":{\"battery\":{\"level\":80}},"
            + "\"timeSeries\":{\"temperature\":[{\"time\":\"2026-04-09T10:36:00.000Z\",\"value\":22.6}]}"
            + "}";

    private static final String SPARSE_JSON = "{"
            + "\"id\":\"rpt-sparse\",\"time\":\"2026-04-09T10:00:00.000Z\","
            + "\"device\":{\"id\":\"dev-sparse\",\"displayId\":\"DS-1\","
            + "\"model\":{\"name\":\"HGD4\",\"family\":\"locoTrack\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1},"
            + "\"labels\":[]},"
            + "\"owner\":{\"id\":\"own-1\",\"name\":\"Owner\"}}";

    private static JsonNode read(String json) {
        try { return MAPPER.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryReportIsTaggedRolePrimary() {
        Map<String, Object> doc = DeviceReportParser.parse(read(PRIMARY_JSON));
        assertEquals("primary", doc.get("role"));
        List<Map<String, Object>> secondaries = (List<Map<String, Object>>) doc.get("secondaries");
        assertEquals(1, secondaries.size());
        assertEquals("rpt-tag-A", secondaries.get(0).get("reportId"));
        assertEquals(Boolean.TRUE, secondaries.get(0).get("isPaired"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void secondaryReportIsTaggedRoleSecondary() {
        Map<String, Object> doc = DeviceReportParser.parse(read(SECONDARY_JSON));
        assertEquals("secondary", doc.get("role"));
        Map<String, Object> relayedBy = (Map<String, Object>) doc.get("relayedBy");
        Map<String, Object> relayDevice = (Map<String, Object>) relayedBy.get("device");
        assertEquals("dev-primary", relayDevice.get("id"));
        assertEquals("rpt-primary-1", relayedBy.get("reportId"));
        assertEquals(-53, relayedBy.get("rssi"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryToSecondaryLinkCanBeTracedBothWays() {
        Map<String, Object> primary = DeviceReportParser.parse(read(PRIMARY_JSON));
        Map<String, Object> secondary = DeviceReportParser.parse(read(SECONDARY_JSON));

        List<Map<String, Object>> secs = (List<Map<String, Object>>) primary.get("secondaries");
        Map<String, Object> matching = secs.stream()
                .filter(s -> s.get("reportId").equals(secondary.get("_id")))
                .findFirst().orElse(null);
        assertNotNull(matching, "primary should list the secondary by its report id");

        Map<String, Object> secDevice = (Map<String, Object>) secondary.get("device");
        assertEquals(secDevice.get("id"), matching.get("id"));

        Map<String, Object> relayedBy = (Map<String, Object>) secondary.get("relayedBy");
        assertEquals(primary.get("_id"), relayedBy.get("reportId"));
    }

    @Test
    void standaloneReportHasNoSecondariesOrRelayedBy() {
        Map<String, Object> doc = DeviceReportParser.parse(read(SPARSE_JSON));
        assertEquals("standalone", doc.get("role"));
        assertFalse(doc.containsKey("secondaries"));
        assertFalse(doc.containsKey("relayedBy"));
    }

    @Test
    void sparseReportOmitsAbsentFieldsEntirely() {
        Map<String, Object> doc = DeviceReportParser.parse(read(SPARSE_JSON));
        for (String k : List.of("rxTime", "location", "sensors", "relayedBy", "thirdPartyObservations", "timeSeries")) {
            assertFalse(doc.containsKey(k), "expected key '" + k + "' to be absent on a sparse report");
        }
        assertEquals("rpt-sparse", doc.get("_id"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void sensorsAreGroupedIntoBatteryEnvironmentAndVectors() {
        Map<String, Object> doc = DeviceReportParser.parse(read(PRIMARY_JSON));
        Map<String, Object> sensors = (Map<String, Object>) doc.get("sensors");
        Map<String, Object> battery = (Map<String, Object>) sensors.get("battery");
        assertEquals(100, battery.get("level"));

        Map<String, Object> env = (Map<String, Object>) sensors.get("environment");
        assertEquals(25.1, env.get("temperature"));

        Map<String, Object> orientation = (Map<String, Object>) sensors.get("orientation");
        assertEquals(-1.0, orientation.get("z"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void thirdPartyObservationKeepsTypeSpecificFields() {
        Map<String, Object> doc = DeviceReportParser.parse(read(PRIMARY_JSON));
        List<Map<String, Object>> tp = (List<Map<String, Object>>) doc.get("thirdPartyObservations");
        Map<String, Object> seal = tp.get(0);
        assertEquals("cableSeal", seal.get("type"));
        assertEquals("closed", seal.get("state"));
        assertEquals(21, seal.get("temperature"));
        assertEquals(7, seal.get("counter"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void unknownThirdPartyTypeFallsBackToRaw() {
        String json = "{\"id\":\"r1\",\"time\":\"t\",\"device\":{\"id\":\"d\",\"displayId\":\"D\","
                + "\"model\":{\"family\":\"locoTrack\",\"name\":\"HGD4\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1},\"labels\":[]},"
                + "\"owner\":{\"id\":\"o\",\"name\":\"O\"},"
                + "\"thirdPartyObservations\":[{\"type\":\"futureBeacon\",\"id\":\"F1\",\"displayId\":\"F1\",\"mystery\":true}]}";
        Map<String, Object> doc = DeviceReportParser.parse(read(json));
        List<Map<String, Object>> tp = (List<Map<String, Object>>) doc.get("thirdPartyObservations");
        Map<String, Object> beacon = tp.get(0);
        assertEquals("futureBeacon", beacon.get("type"));
        Map<String, Object> raw = (Map<String, Object>) beacon.get("raw");
        assertEquals(Boolean.TRUE, raw.get("mystery"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void timeSeriesPassesThroughAndDropsEmptySeries() {
        Map<String, Object> doc = DeviceReportParser.parse(read(SECONDARY_JSON));
        Map<String, Object> ts = (Map<String, Object>) doc.get("timeSeries");
        List<Object> temp = (List<Object>) ts.get("temperature");
        assertEquals(1, temp.size());
    }

    @SuppressWarnings("unchecked")
    @Test
    void projectLatestStateReturnsPerDeviceSnapshot() {
        Map<String, Object> doc = DeviceReportParser.parse(read(PRIMARY_JSON));
        Map<String, Object> state = DeviceReportParser.projectLatestState(doc);
        Map<String, Object> device = (Map<String, Object>) doc.get("device");
        assertEquals(device.get("id"), state.get("_id"));
        Map<String, Object> lastReport = (Map<String, Object>) state.get("lastReport");
        assertEquals(doc.get("_id"), lastReport.get("id"));
        assertEquals("primary", lastReport.get("role"));
        assertNotNull(state.get("lastSensors"));
        assertNotNull(state.get("lastLocation"));
    }

    @Test
    void projectLatestStateOmitsAbsentOptionals() {
        Map<String, Object> doc = DeviceReportParser.parse(read(SPARSE_JSON));
        Map<String, Object> state = DeviceReportParser.projectLatestState(doc);
        assertEquals("dev-sparse", state.get("_id"));
        assertFalse(state.containsKey("lastLocation"));
        assertFalse(state.containsKey("lastSensors"));
        assertFalse(state.containsKey("relayedBy"));
    }

    @Test
    void parseReturnsNullForNullInput() {
        assertNull(DeviceReportParser.parse(null));
    }

    @Test
    void parseHandlesMissingNodeGracefully() {
        JsonNode missing = MAPPER.createObjectNode().path("absent");
        assertNull(DeviceReportParser.parse(missing));
        assertTrue(missing.isMissingNode());
    }
}
