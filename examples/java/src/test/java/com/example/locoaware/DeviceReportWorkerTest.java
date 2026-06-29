package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeviceReportWorkerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Repositories.DeviceReports deviceReports;
    private Repositories.Devices devices;
    private Repositories.Shipments shipments;
    private DeviceReportWorker worker;

    @BeforeEach
    void setUp() {
        deviceReports = new Repositories.DeviceReports();
        devices = new Repositories.Devices();
        shipments = new Repositories.Shipments();
        worker = new DeviceReportWorker(shipments, devices, deviceReports);
    }

    private static final String PRIMARY = "{"
            + "\"id\":\"rpt-primary-1\","
            + "\"time\":\"2026-04-09T10:47:58.612Z\","
            + "\"device\":{\"id\":\"dev-primary\",\"displayId\":\"HGD-1\","
            + "\"model\":{\"family\":\"locoTrack\",\"name\":\"HGD4\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1},"
            + "\"labels\":[]},"
            + "\"owner\":{\"id\":\"own-1\",\"name\":\"Acme\"},"
            + "\"sensors\":{\"battery\":{\"level\":90},\"temperature\":20},"
            + "\"secondaryObservations\":["
            + "{\"id\":\"dev-tag-A\",\"displayId\":\"TAG-A\","
            + "\"model\":{\"family\":\"locoCard\",\"name\":\"E1BL\",\"product\":\"LocoCard\",\"version\":1,\"revision\":0},"
            + "\"reportType\":\"presence\",\"reportId\":\"rpt-tag-A\",\"rssi\":-50,\"isPaired\":true},"
            + "{\"id\":\"dev-tag-B\",\"displayId\":\"TAG-B\","
            + "\"model\":{\"family\":\"locoCard\",\"name\":\"E1BL\",\"product\":\"LocoCard\",\"version\":1,\"revision\":0},"
            + "\"reportType\":\"event\",\"reportId\":\"rpt-tag-B\",\"rssi\":-70,\"isPaired\":true}"
            + "]}";

    private static final String SECONDARY = "{"
            + "\"id\":\"rpt-tag-A\",\"time\":\"2026-04-09T10:48:51.000Z\","
            + "\"device\":{\"id\":\"dev-tag-A\",\"displayId\":\"TAG-A\","
            + "\"model\":{\"family\":\"locoCard\",\"name\":\"E1BL\",\"product\":\"LocoCard\",\"version\":1,\"revision\":0},"
            + "\"labels\":[]},"
            + "\"owner\":{\"id\":\"own-1\",\"name\":\"Acme\"},"
            + "\"reportedBy\":{"
            + "\"device\":{\"id\":\"dev-primary\",\"displayId\":\"HGD-1\","
            + "\"model\":{\"family\":\"locoTrack\",\"name\":\"HGD4\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1}},"
            + "\"report\":{\"id\":\"rpt-primary-1\",\"time\":\"2026-04-09T10:47:58.612Z\"},"
            + "\"rssi\":-50},"
            + "\"sensors\":{\"battery\":{\"level\":80}}}";

    private static JsonNode read(String json) {
        try { return MAPPER.readTree(json); } catch (Exception e) { throw new RuntimeException(e); }
    }

    @SuppressWarnings("unchecked")
    @Test
    void primaryStoresReportAndTouchesEachSecondary() {
        worker.handleDeviceReport(read(PRIMARY));

        Map<String, Object> stored = deviceReports.get("rpt-primary-1");
        assertEquals("primary", stored.get("role"));
        assertEquals(2, ((List<?>) stored.get("secondaries")).size());

        Map<String, Object> primaryDev = devices.get("dev-primary");
        Map<String, Object> lastReport = (Map<String, Object>) primaryDev.get("lastReport");
        assertEquals("rpt-primary-1", lastReport.get("id"));
        Map<String, Object> lastSensors = (Map<String, Object>) primaryDev.get("lastSensors");
        Map<String, Object> battery = (Map<String, Object>) lastSensors.get("battery");
        assertEquals(90, battery.get("level"));

        Map<String, Object> tagA = devices.get("dev-tag-A");
        Map<String, Object> tagB = devices.get("dev-tag-B");
        Map<String, Object> seenByA = (Map<String, Object>) tagA.get("lastSeenBy");
        Map<String, Object> seenByB = (Map<String, Object>) tagB.get("lastSeenBy");
        assertEquals("dev-primary", seenByA.get("primaryDeviceId"));
        assertEquals(-50, seenByA.get("rssi"));
        assertEquals(-70, seenByB.get("rssi"));
        assertFalse(tagA.containsKey("lastSensors"), "primary presence touch must not write sensor data");
    }

    @SuppressWarnings("unchecked")
    @Test
    void secondaryWritesAuthoritativeSensorsAndPrimaryDoesNotClobber() {
        worker.handleDeviceReport(read(SECONDARY));
        worker.handleDeviceReport(read(PRIMARY));

        Map<String, Object> tagA = devices.get("dev-tag-A");
        Map<String, Object> lastSensors = (Map<String, Object>) tagA.get("lastSensors");
        Map<String, Object> battery = (Map<String, Object>) lastSensors.get("battery");
        assertEquals(80, battery.get("level"));

        Map<String, Object> seenBy = (Map<String, Object>) tagA.get("lastSeenBy");
        assertEquals("dev-primary", seenBy.get("primaryDeviceId"));

        Map<String, Object> lastReport = (Map<String, Object>) tagA.get("lastReport");
        assertEquals("secondary", lastReport.get("role"));
    }

    @Test
    void fallsBackToRelayPrimaryWhenTagHasNoShipmentBinding() {
        shipments.seed("ship-7", Map.of("boundDeviceIds", List.of("dev-primary")));

        worker.handleDeviceReport(read(SECONDARY));

        Map<String, Object> ship = shipments.get("ship-7");
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) ship.get("events");
        assertEquals(1, events.size());
    }

    @Test
    void appendsToShipmentMatchedByDeviceName() {
        shipments.seed("ship-42", Map.of("boundDeviceNames", List.of("SHIP-42")));

        // PRIMARY with device.name added.
        String withName = PRIMARY.replace("\"labels\":[]", "\"name\":\"SHIP-42\",\"labels\":[]");
        worker.handleDeviceReport(read(withName));

        Map<String, Object> ship = shipments.get("ship-42");
        @SuppressWarnings("unchecked")
        List<Object> events = (List<Object>) ship.get("events");
        assertEquals(1, events.size());
    }

    @Test
    void isNullSafeForMinimalReport() {
        String minimal = "{\"id\":\"rpt-empty\",\"time\":\"2026-04-09T10:00:00.000Z\","
                + "\"device\":{\"id\":\"dev-empty\",\"displayId\":\"X\","
                + "\"model\":{\"family\":\"locoTrack\",\"name\":\"HGD4\",\"product\":\"LocoTrack\",\"version\":4,\"revision\":1},"
                + "\"labels\":[]},\"owner\":{\"id\":\"own-1\",\"name\":\"Acme\"}}";
        worker.handleDeviceReport(read(minimal));

        Map<String, Object> stored = deviceReports.get("rpt-empty");
        assertEquals("standalone", stored.get("role"));
        assertNotNull(devices.get("dev-empty"));
        assertNull(devices.get("dev-empty").get("lastSensors"));
    }
}
