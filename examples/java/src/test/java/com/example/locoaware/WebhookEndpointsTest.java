package com.example.locoaware;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = MOCK)
@AutoConfigureMockMvc
class WebhookEndpointsTest {

    @Autowired MockMvc mvc;
    @Autowired RecordingQueueClient queue;
    @Autowired RecordingShipments shipments;
    @Autowired RecordingDevices devices;
    @Autowired RecordingAssets assets;

    @BeforeEach
    void resetRecordings() {
        queue.published.clear();
        shipments.appended.clear();
        shipments.deviceLookups.clear();
        shipments.assetLookups.clear();
        devices.updates.clear();
        assets.updates.clear();
    }

    @Test
    void shipmentsEndpointReturns200() throws Exception {
        mvc.perform(post("/webhooks/shipments")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_1\",\"type\":\"positionReport\",\"shipment\":{\"id\":\"ship_1\"}}"))
                .andExpect(status().isOk());

        assertEquals(1, shipments.appended.size());
        assertEquals("ship_1", shipments.appended.get(0).shipmentId);
    }

    @Test
    void shipmentReportEventSyncsTheEmbeddedDevice() throws Exception {
        mvc.perform(post("/webhooks/shipments")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_2\",\"type\":\"report\",\"shipment\":{\"id\":\"ship_2\"}," +
                                "\"payload\":{\"device\":{\"id\":\"dev_2\"}}}"))
                .andExpect(status().isOk());

        assertTrue(devices.updates.stream().anyMatch(id -> id.equals("dev_2")),
                "expected device dev_2 to be updated");
    }

    @Test
    void assetsEndpointReturns200AndLooksUpShipment() throws Exception {
        mvc.perform(post("/webhooks/assets")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_3\",\"asset\":{\"id\":\"asset_3\",\"name\":\"pallet-3\"}}"))
                .andExpect(status().isOk());

        assertEquals(1, shipments.assetLookups.size());
        assertEquals("pallet-3", shipments.assetLookups.get(0)[0]);
        assertEquals("asset_3",  shipments.assetLookups.get(0)[1]);
    }

    @Test
    void deviceEventsEndpointReturns202AndEnqueues() throws Exception {
        mvc.perform(post("/webhooks/device-events")
                        .contentType("application/json")
                        .content("{\"id\":\"evt_4\",\"device\":{\"id\":\"dev_4\"}}"))
                .andExpect(status().isAccepted());

        assertEquals(1, queue.published.size());
        assertEquals("locoaware.device-events", queue.published.peek().topic);
    }

    @Test
    void deviceReportsEndpointReturns202AndEnqueues() throws Exception {
        mvc.perform(post("/webhooks/device-reports")
                        .contentType("application/json")
                        .content("{\"device\":{\"id\":\"dev_5\",\"name\":\"ship_5\"}}"))
                .andExpect(status().isAccepted());

        assertEquals(1, queue.published.size());
        assertEquals("locoaware.device-reports", queue.published.peek().topic);
    }

    // ------------------------------------------------------------------------
    // Recording stand-ins. Spring picks these as @Primary, replacing the real
    // beans without needing Mockito (which doesn't yet support Java 24).
    // ------------------------------------------------------------------------
    @TestConfiguration
    static class TestConfig {
        @Bean @Primary RecordingQueueClient recordingQueue(ApplicationEventPublisher bus) {
            return new RecordingQueueClient(bus);
        }
        @Bean @Primary RecordingShipments recordingShipments() { return new RecordingShipments(); }
        @Bean @Primary RecordingDevices   recordingDevices()   { return new RecordingDevices(); }
        @Bean @Primary RecordingAssets    recordingAssets()    { return new RecordingAssets(); }
    }

    static class RecordingQueueClient extends QueueClient {
        record Published(String topic, JsonNode body) {}
        final ConcurrentLinkedQueue<Published> published = new ConcurrentLinkedQueue<>();

        RecordingQueueClient(ApplicationEventPublisher bus) { super(bus); }

        @Override
        public void publish(String topic, JsonNode message) {
            published.add(new Published(topic, message));
            super.publish(topic, message);
        }
    }

    static class RecordingShipments extends Repositories.Shipments {
        record Append(String shipmentId, JsonNode event) {}
        final List<Append> appended = new ArrayList<>();
        final List<String[]> deviceLookups = new ArrayList<>();
        final List<String[]> assetLookups = new ArrayList<>();

        @Override public void appendEvent(String shipmentId, JsonNode event) {
            appended.add(new Append(shipmentId, event));
        }
        @Override public String findIdByDeviceNameOrId(String deviceName, String deviceId) {
            deviceLookups.add(new String[]{deviceName, deviceId});
            return null;
        }
        @Override public String findIdByAssetNameOrId(String assetName, String assetId) {
            assetLookups.add(new String[]{assetName, assetId});
            return null;
        }
    }

    static class RecordingDevices extends Repositories.Devices {
        final List<String> updates = new ArrayList<>();
        @Override public void updateLatest(String deviceId, JsonNode event) { updates.add(deviceId); }
    }

    static class RecordingAssets extends Repositories.Assets {
        final List<String> updates = new ArrayList<>();
        @Override public void updateLatest(String assetId, JsonNode event) { updates.add(assetId); }
    }
}
