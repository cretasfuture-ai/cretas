package com.cretas.aims.audit.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ClientErrorControllerContractTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ClientErrorRateLimiter limiter = new ClientErrorRateLimiter(
                Clock.fixed(Instant.parse("2026-09-12T00:00:00Z"), ZoneOffset.UTC));
        mvc = MockMvcBuilders.standaloneSetup(new ClientErrorController(limiter))
                .addFilters(new ClientErrorBodyLimitFilter())
                .build();
    }

    @Test
    void requiresAuthenticatedTrustedFactoryContext() throws Exception {
        mvc.perform(post("/api/mobile/F006/client-errors")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/mobile/F006/client-errors")
                        .requestAttr("userId", 7L)
                        .requestAttr("factoryId", "F999")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isForbidden());
    }

    @Test
    void acceptsOnlyBoundedTechnicalMetadataAndLimitsAnIdentity() throws Exception {
        for (int i = 0; i < 10; i++) {
            mvc.perform(post("/api/mobile/F006/client-errors")
                            .requestAttr("userId", 7L)
                            .requestAttr("factoryId", "F006")
                            .contentType("application/json")
                            .content(validBody()))
                    .andExpect(status().isOk());
        }
        mvc.perform(post("/api/mobile/F006/client-errors")
                        .requestAttr("userId", 7L)
                        .requestAttr("factoryId", "F006")
                        .contentType("application/json")
                        .content(validBody()))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void rejectsMessagesAndQueryBearingRoutes() throws Exception {
        mvc.perform(post("/api/mobile/F006/client-errors")
                        .requestAttr("userId", 7L)
                        .requestAttr("factoryId", "F006")
                        .contentType("application/json")
                        .content(validBody().replace("/warehouse/list", "/warehouse/list?password=secret")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsOversizedChunkBeforeJsonBinding() throws Exception {
        mvc.perform(post("/api/mobile/F006/client-errors")
                        .requestAttr("userId", 7L)
                        .requestAttr("factoryId", "F006")
                        .header("Transfer-Encoding", "chunked")
                        .contentType("application/json")
                        .content("{" + "\"route\":\"/x\",\"padding\":\"" + "x".repeat(9000) + "\"}"))
                .andExpect(status().isPayloadTooLarge());
    }

    private String validBody() {
        return "{\"eventId\":\"evt-1\",\"category\":\"request_failure\","
                + "\"route\":\"/warehouse/list\",\"method\":\"GET\",\"status\":500,"
                + "\"errorCode\":\"SERVER_ERROR\",\"parentCorrelationId\":\"corr-1\","
                + "\"source\":\"web-admin\",\"file\":\"app.js\",\"line\":10,\"column\":2}";
    }
}
