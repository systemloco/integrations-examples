package com.example.locoaware;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "locoaware.webhook.secret=shhh")
class HmacSignatureFilterTest {

    private static final String SECRET = "shhh";

    @Autowired MockMvc mvc;

    private static String sign(String body, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getEncoder().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsWithMissingSignature() throws Exception {
        mvc.perform(post("/webhooks/shipments")
                        .contentType("application/json")
                        .content("{\"shipment\":{\"id\":\"x\"}}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void rejectsWithBadSignature() throws Exception {
        String body = "{\"shipment\":{\"id\":\"x\"}}";
        mvc.perform(post("/webhooks/shipments")
                        .contentType("application/json")
                        .header("X-LocoAware-Signature", sign(body, "wrong-secret"))
                        .content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void acceptsCorrectlySignedRequest() throws Exception {
        String body = "{\"id\":\"evt_signed\",\"shipment\":{\"id\":\"ship_signed\"}}";
        mvc.perform(post("/webhooks/shipments")
                        .contentType("application/json")
                        .header("X-LocoAware-Signature", sign(body, SECRET))
                        .content(body))
                .andExpect(status().isOk());
    }

    @Test
    void acceptsSignedDeviceReportAndReturns202() throws Exception {
        String body = "{\"device\":{\"id\":\"dev_signed\"}}";
        mvc.perform(post("/webhooks/device-reports")
                        .contentType("application/json")
                        .header("X-LocoAware-Signature", sign(body, SECRET))
                        .content(body))
                .andExpect(status().isAccepted());
    }
}
