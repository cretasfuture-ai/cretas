package com.cretas.aims.controller;

import com.cretas.aims.dto.common.PageRequest;
import com.cretas.aims.dto.common.PageResponse;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.repository.QualityInspectionRepository;
import com.cretas.aims.service.AIEnterpriseService;
import com.cretas.aims.service.MobileService;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.ProcessingStageRecordService;
import com.cretas.aims.service.QualityDispositionRuleService;
import com.cretas.aims.service.SpecialApprovalService;
import com.cretas.aims.service.wip.WipInventoryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 缺陷 50 第二步 (2026-09-02, LIUSHANMEN prod owner-hit) —— controller 层 MockMvc,
 * 验证 {@code GET /processing/batches-with-clerk-wip} 的 {@code batchNumber} 查询参数
 * 真的从 HTTP 请求绑定到了 {@link ProcessingService#getBatches(String, String, Long,
 * Boolean, Boolean, String, PageRequest)}。
 *
 * <p>之前这个方法根本没有 {@code batchNumber} 形参 —— Spring 静默丢弃前端一直在发的
 * 同名参数, 这里的第一个用例直接证伪那个"被丢弃"的旧行为: 断言请求参数真的原样
 * 传到了 service 调用的实参上, 不是"发了但没人接"。
 */
@ExtendWith(MockitoExtension.class)
class ProcessingControllerBatchNumberSearchTest {

    private static final String FACTORY_ID = "F-LIUSHANMEN";

    @Mock ProcessingService processingService;
    @Mock MobileService mobileService;
    @Mock AIEnterpriseService aiEnterpriseService;
    @Mock ProcessingStageRecordService stageRecordService;
    @Mock QualityDispositionRuleService qualityDispositionRuleService;
    @Mock SpecialApprovalService specialApprovalService;
    @Mock QualityInspectionRepository qualityInspectionRepository;
    @Mock WipInventoryService wipInventoryService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        ProcessingController controller = new ProcessingController(
                processingService, mobileService, aiEnterpriseService, stageRecordService,
                qualityDispositionRuleService, specialApprovalService, qualityInspectionRepository,
                wipInventoryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    private ProductionBatch batch(String batchNumber) {
        ProductionBatch b = new ProductionBatch();
        b.setId(10884L);
        b.setFactoryId(FACTORY_ID);
        b.setBatchNumber(batchNumber);
        b.setStatus(com.cretas.aims.entity.enums.ProductionBatchStatus.IN_PROGRESS);
        b.setQuantity(new BigDecimal("100.00"));
        b.setUnit("kg");
        return b;
    }

    @Test
    @DisplayName("带 batchNumber=PB-PLAN-1788008667414 → 请求参数真的原样传到 service, 只返回命中行")
    void withBatchNumberParam_threadsToServiceAndReturnsOnlyMatchingRow() throws Exception {
        String keyword = "PB-PLAN-1788008667414";
        ProductionBatch matched = batch("PB-PLAN-1788008667414-B141A9D8-8026");
        when(processingService.getBatches(
                eq(FACTORY_ID), isNull(), isNull(), isNull(), eq(true), eq(keyword), any(PageRequest.class)))
                .thenReturn(PageResponse.of(List.of(matched), 1, 20, 1L));

        mockMvc.perform(get("/api/mobile/{factoryId}/processing/batches-with-clerk-wip", FACTORY_ID)
                        .param("batchNumber", keyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].batchNumber").value(matched.getBatchNumber()))
                .andExpect(jsonPath("$.data.totalElements").value(1));

        ArgumentCaptor<String> batchNumberCaptor = ArgumentCaptor.forClass(String.class);
        verify(processingService).getBatches(
                eq(FACTORY_ID), isNull(), isNull(), isNull(), eq(true),
                batchNumberCaptor.capture(), any(PageRequest.class));
        assertEquals(keyword, batchNumberCaptor.getValue(),
                "HTTP query param 必须原样到达 service 调用 —— 这就是缺陷 50 之前从来没发生过的事");
    }

    @Test
    @DisplayName("不带 batchNumber 参数 → 原行为不变, service 收到 batchNumber=null")
    void withoutBatchNumberParam_originalBehaviorUnchanged() throws Exception {
        ProductionBatch unrelated1 = batch("PB-AAA-001");
        ProductionBatch unrelated2 = batch("PB-BBB-002");
        when(processingService.getBatches(
                eq(FACTORY_ID), isNull(), isNull(), isNull(), eq(true), isNull(), any(PageRequest.class)))
                .thenReturn(PageResponse.of(List.of(unrelated1, unrelated2), 1, 20, 2L));

        mockMvc.perform(get("/api/mobile/{factoryId}/processing/batches-with-clerk-wip", FACTORY_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2));

        ArgumentCaptor<String> batchNumberCaptor = ArgumentCaptor.forClass(String.class);
        verify(processingService).getBatches(
                eq(FACTORY_ID), isNull(), isNull(), isNull(), eq(true),
                batchNumberCaptor.capture(), any(PageRequest.class));
        assertNull(batchNumberCaptor.getValue(), "不带参数时不该凭空造出一个搜索词");
    }

    @Test
    @DisplayName("阴性: 不存在的批次号 → 空页 (total 0), 不是退回全量列表")
    void withNonexistentBatchNumber_returnsEmptyPageNotFullList() throws Exception {
        String bogusKeyword = "NOBODY-HAS-THIS-BATCH-NUMBER-404";
        when(processingService.getBatches(
                eq(FACTORY_ID), isNull(), isNull(), isNull(), eq(true), eq(bogusKeyword), any(PageRequest.class)))
                .thenReturn(PageResponse.of(Collections.emptyList(), 1, 20, 0L));

        mockMvc.perform(get("/api/mobile/{factoryId}/processing/batches-with-clerk-wip", FACTORY_ID)
                        .param("batchNumber", bogusKeyword))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }
}
