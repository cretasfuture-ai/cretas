package com.cretas.aims.controller;

import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.dto.user.UserDTO;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.inventory.OpeningInventoryService;
import com.cretas.aims.security.PriceMaskResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * #1844 终审 L-d: POST /material-batches/{id}/source-individual/unlink 这个<b>HTTP 入口</b>。
 *
 * <p>守: 原因走请求体并被 @Valid 校验(缺 / 空白 / 超 255 → 400, service 一次都没被调);
 * 合法时把 token 用户、路径上的 factoryId 与 batchId、请求属性里的角色原样交给 service。
 */
class MaterialBatchUnlinkSourceIndividualEndpointContractTest {

    private static final String URL = "/api/mobile/F1/material-batches/B-1/source-individual/unlink";

    private MaterialBatchService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MaterialBatchService.class);
        MobileService mobileService = mock(MobileService.class);
        when(mobileService.getUserFromToken(anyString())).thenReturn(UserDTO.builder().id(42L).build());
        MaterialBatchController controller = new MaterialBatchController(
                service, mobileService, mock(PriceMaskResolver.class), mock(OpeningInventoryService.class));
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private static MockHttpServletRequestBuilder req() {
        return post(URL).header("Authorization", "Bearer test-token")
                .contentType(MediaType.APPLICATION_JSON)
                .requestAttr("role", "warehouse_manager");
    }

    @Test
    @DisplayName("缺请求体 / 空对象 / 空白原因 / 超 255 字 → 400, service 未被调用")
    void invalidReasonIs400BeforeService() throws Exception {
        mvc.perform(req()).andExpect(status().isBadRequest());
        mvc.perform(req().content("{}")).andExpect(status().isBadRequest());
        mvc.perform(req().content("{\"reason\":\"   \"}")).andExpect(status().isBadRequest());
        mvc.perform(req().content("{\"reason\":\"" + "x".repeat(256) + "\"}")).andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("带原因 → 200, token 用户 / factoryId / batchId / 角色原样交给 service")
    void validReasonDelegatesWithTokenUser() throws Exception {
        when(service.unlinkSourceIndividual(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(MaterialBatchDTO.builder().id("B-1").build());

        mvc.perform(req().content("{\"reason\":\"挂错了个体\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.id").value("B-1"));

        verify(service).unlinkSourceIndividual(eq("F1"), eq("B-1"), eq("挂错了个体"), eq(42L), eq("warehouse_manager"));
    }
}
