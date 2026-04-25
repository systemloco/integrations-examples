package com.example.locoaware;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoriesTest {

    @Test
    void devicesExistsTreatsNonNullIdAsExisting() {
        Repositories.Devices devices = new Repositories.Devices();
        assertTrue(devices.exists("dev_1"));
    }

    @Test
    void devicesExistsTreatsNullIdAsMissing() {
        Repositories.Devices devices = new Repositories.Devices();
        assertFalse(devices.exists(null));
    }

    @Test
    void assetsExistsTreatsNonNullIdAsExisting() {
        Repositories.Assets assets = new Repositories.Assets();
        assertTrue(assets.exists("asset_1"));
    }

    @Test
    void shipmentLookupReturnsNullInTheStandIn() {
        Repositories.Shipments shipments = new Repositories.Shipments();
        assertNull(shipments.findIdByDeviceNameOrId("ship_1", "dev_1"));
        assertNull(shipments.findIdByAssetNameOrId("pallet-1", "asset_1"));
    }

    @Test
    void appendEventDoesNotThrow() throws Exception {
        Repositories.Shipments shipments = new Repositories.Shipments();
        shipments.appendEvent("ship_1", new ObjectMapper().readTree("{\"id\":\"evt_1\"}"));
    }
}
