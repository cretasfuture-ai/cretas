package com.cretas.aims.controller;

import com.cretas.aims.ai.client.DashScopeClient;
import com.cretas.aims.ai.tool.ToolRegistry;
import com.cretas.aims.ai.tool.gateway.ToolExecutionGateway;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.entity.enums.FactoryUserRole;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.PreviewTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class CanvasAIPlanConfirmationContractTest {
    private static final String FACTORY = "LOCAL_B05";
    private static final String TOOL = "canvas_work_process_catalog";
    private PreviewTokenService previewTokens;
    private ToolExecutionGateway gateway;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        ToolRegistry registry = mock(ToolRegistry.class);
        MobileService mobile = mock(MobileService.class);
        previewTokens = mock(PreviewTokenService.class);
        gateway = mock(ToolExecutionGateway.class);
        UserDTO user = new UserDTO();
        user.setId(7L);
        user.setFactoryId(FACTORY);
        user.setFactoryType("FACTORY");
        user.setRoleCode(FactoryUserRole.permission_admin);
        when(mobile.getUserFromToken("local-test")).thenReturn(user);
        CanvasAIController controller = new CanvasAIController(
                registry, new ObjectMapper(), mock(DashScopeClient.class), mobile, gateway);
        ReflectionTestUtils.setField(controller, "previewTokenService", previewTokens);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void missingProofNeverReachesGateway() throws Exception {
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verifyNoInteractions(gateway);
    }

    @Test
    void wrongTenantBindingFailsBeforeGateway() throws Exception {
        when(previewTokens.readBoundSnapshot("proof", FACTORY, 7L)).thenReturn(Optional.empty());
        mvc.perform(post("/api/mobile/{factoryId}/config/v2/ai/apply-diffs", FACTORY)
                        .header("Authorization", "Bearer local-test")
                        .header("X-Canvas-Result-Protocol", "3")
                        .contentType("application/json")
                        .content("[{\"tool\":\"" + TOOL + "\",\"params\":{},"
                                + "\"proof\":{\"token\":\"proof\",\"commandDigest\":\"bad\","
                                + "\"descriptorVersion\":\"1.1.0\"}}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].status").value("FAILURE"));
        verifyNoInteractions(gateway);
    }
}
