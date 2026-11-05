package com.cretas.aims.controller;

import com.cretas.aims.config.PermissionInterceptor;
import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.dto.material.MaterialBatchDTO;
import com.cretas.aims.entity.User;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.exception.GlobalExceptionHandler;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.PermissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DisposalMaterialBatchOptionsContractTest {
    @Mock MaterialBatchService batches;
    @Mock PermissionService permissions;
    @Mock UserRepository users;
    MockMvc mvc;
    User actor;
    static final String URL = "/api/mobile/F006/disposal-records/eligible-material-batches";

    @BeforeEach
    void setup() {
        actor = new User(); actor.setId(88L); actor.setFactoryId("F006");
        when(users.findById(88L)).thenReturn(Optional.of(actor));
        mvc = MockMvcBuilders.standaloneSetup(new DisposalMaterialBatchOptionsController(batches))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new PermissionInterceptor(permissions, users, new ObjectMapper())).build();
    }

    void grant(String permission) {
        when(permissions.hasAnyPermission(eq(actor), any(String[].class))).thenAnswer(invocation ->
                Arrays.stream(invocation.getArguments()).skip(1).anyMatch(permission::equals));
    }

    MaterialBatchDTO batch(String factoryId) {
        MaterialBatchDTO value = new MaterialBatchDTO();
        value.setId("BATCH-A"); value.setFactoryId(factoryId); value.setBatchNumber("QA-BATCH-A");
        value.setMaterialTypeId("RMT-A"); value.setMaterialName("测试原料");
        value.setCurrentQuantity(new BigDecimal("10")); value.setQuantityUnit("kg");
        value.setStatus(MaterialBatchStatus.AVAILABLE);
        return value;
    }

    @Test
    void qualityReadWithoutInventoryPermissionGetsOnlyNarrowFieldsAndServerSideAvailableFilter() throws Exception {
        grant("quality:read");
        when(batches.getMaterialBatchList(eq("F006"), any())).thenReturn(PageResponse.of(List.of(batch("F006")), 2, 25, 101L));
        var response = mvc.perform(get(URL).param("page", "2").param("size", "25").param("keyword", "QA-BATCH")
                        .requestAttr("userId", 88L))
                .andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].currentQuantity").value(10))
                .andExpect(jsonPath("$.data.content[0].quantityUnit").value("kg"))
                .andExpect(jsonPath("$.data.totalElements").value(101)).andReturn();
        var body = new ObjectMapper().readTree(response.getResponse().getContentAsString());
        assertThat(body.path("data").path("content").get(0).properties()).extracting(java.util.Map.Entry::getKey)
                .containsExactlyInAnyOrder("id", "batchNumber", "materialTypeId", "materialName", "currentQuantity", "quantityUnit");
        var request = ArgumentCaptor.forClass(PageRequest.class);
        verify(batches).getMaterialBatchList(eq("F006"), request.capture());
        assertThat(request.getValue().getStatus()).isEqualTo("AVAILABLE");
        assertThat(request.getValue().getPage()).isEqualTo(2);
        assertThat(request.getValue().getKeyword()).isEqualTo("QA-BATCH");
        assertThat(request.getValue().getSortBy()).isEqualTo("createdAt");
    }

    @Test
    void warehouseWriterKeepsExistingDisposalSelectorAccess() throws Exception {
        grant("warehouse:read_write");
        when(batches.getMaterialBatchList(eq("F006"), any())).thenReturn(PageResponse.of(List.of(), 1, 100, 0L));
        mvc.perform(get(URL).requestAttr("userId", 88L)).andExpect(status().isOk());
    }

    @Test
    void unrelatedInventoryReadAloneCannotReadQualityCandidates() throws Exception {
        grant("inventory:read");
        mvc.perform(get(URL).requestAttr("userId", 88L)).andExpect(status().isForbidden());
        verifyNoInteractions(batches);
    }

    @Test
    void foreignFactoryRowsAreRejectedRatherThanProjectedOrSilentlyFiltered() throws Exception {
        grant("quality:read_write");
        when(batches.getMaterialBatchList(eq("F006"), any())).thenReturn(PageResponse.of(List.of(batch("F007")), 1, 100, 1L));
        mvc.perform(get(URL).requestAttr("userId", 88L)).andExpect(status().isForbidden());
    }

    @Test
    void nonAvailableRowsCannotBeReturnedByTheQualitySelector() throws Exception {
        grant("quality:read"); var value = batch("F006"); value.setStatus(MaterialBatchStatus.FROZEN);
        when(batches.getMaterialBatchList(eq("F006"), any())).thenReturn(PageResponse.of(List.of(value), 1, 100, 1L));
        mvc.perform(get(URL).requestAttr("userId", 88L)).andExpect(status().isForbidden());
    }
}
