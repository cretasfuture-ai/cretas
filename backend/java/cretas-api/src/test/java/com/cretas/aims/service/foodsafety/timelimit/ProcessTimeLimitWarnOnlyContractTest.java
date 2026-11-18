package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.dto.common.ApiResponse;
import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.workflow.ProductionWorkflowInstance;
import com.cretas.aims.entity.workflow.WorkflowTaskPort;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeMarkRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.workflow.ProductionWorkflowInstanceRepository;
import com.cretas.aims.repository.workflow.WorkflowTaskPortRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.canvas.ThresholdKeys;
import com.cretas.aims.service.discipline.DisciplineFixtures;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.quality.release.QcReleaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * PROTO-Q41 (Steve 2026-11-18 拍板「默认只警告」): 工序时限超时 = 工厂开关 {@code production.time_limit.block_on_exceed}。
 * <ul>
 *   <li>默认 (没配 / 0): 超时照常投料 + 写 1 条 OPEN 留痕 + 警告带回; 未登记开始解冻照常投料 + 警告 (起算点不知道, 不写留痕)。</li>
 *   <li>开 (1): 原行为 409 BLOCKING, 超时同样写留痕 (阴性对照)。</li>
 * </ul>
 * 「带回调用方」按真实 MVC 链验: 服务判定 → {@link TimeLimitWarnings} → {@link TimeLimitWarningResponseAdvice} → JSON。
 */
class ProcessTimeLimitWarnOnlyContractTest {

    private static final String F = "F006";
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 11, 4, 8, 0);
    private static final long INSTANCE = 9L;
    private static final long TASK = 3L;

    private final WorkflowTaskPortRepository portRepo = mock(WorkflowTaskPortRepository.class);
    private final WorkProcessTaskRepository taskRepo = mock(WorkProcessTaskRepository.class);
    private final ProductionBatchRepository batchRepo = mock(ProductionBatchRepository.class);
    private final ProductionWorkflowInstanceRepository instanceRepo = mock(ProductionWorkflowInstanceRepository.class);
    private final ProductionReportRepository reportRepo = mock(ProductionReportRepository.class);
    private final SemiFinishedInventoryRepository semiRepo = mock(SemiFinishedInventoryRepository.class);
    private final SemiFinishedInventoryTransactionRepository txnRepo = mock(SemiFinishedInventoryTransactionRepository.class);
    private final MaterialBatchRepository materialRepo = mock(MaterialBatchRepository.class);
    private final BatchLineageEdgeRepository edgeRepo = mock(BatchLineageEdgeRepository.class);
    private final ProcessTimeMarkRepository markRepo = mock(ProcessTimeMarkRepository.class);
    private final ProcessTimeLimitEventRepository eventRepo = mock(ProcessTimeLimitEventRepository.class);
    private final ProcessTimeLimitEventWriter writer = mock(ProcessTimeLimitEventWriter.class);
    private final ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);

    private LocalDateTime now = T0;
    private ProcessTimeLimitService service;
    private WorkProcessTask task;

    @BeforeEach
    void setUp() {
        service = new ProcessTimeLimitService(portRepo, taskRepo, mock(WorkProcessRepository.class), batchRepo,
                planRepo, instanceRepo, reportRepo, semiRepo, txnRepo, materialRepo,
                mock(FinishedGoodsBatchRepository.class), edgeRepo, markRepo, eventRepo, writer,
                mock(QcReleaseService.class), mock(WarehouseResolver.class),
                mock(com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository.class),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                new com.cretas.aims.service.processentry.IntermediateOwnershipGuard(planRepo, batchRepo,
                        mock(com.cretas.aims.repository.CustomerRepository.class))) {
            @Override
            protected LocalDateTime now() {
                return now;
            }
        };
        // 默认档 = 迁移全局默认行 (block_on_exceed = 0): 真实的 FactoryDisciplinePolicy, 表里没有这个 key
        service.setDisciplinePolicy(DisciplineFixtures.defaults());
        task = new WorkProcessTask();
        task.setId(TASK);
        task.setFactoryId(F);
        task.setWorkflowInstanceId(INSTANCE);
        task.setProcessOrder(2);
        when(taskRepo.findByFactoryIdAndId(F, TASK)).thenReturn(Optional.of(task));
        when(eventRepo.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(eq(F), anyString(), anyString(),
                eq(ProcessTimeLimitEvent.Status.RELEASED))).thenReturn(List.of());
        when(markRepo.findByTargets(eq(F), anyString(), any())).thenReturn(List.of());
    }

    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }

    private void blockOn() {
        service.setDisciplinePolicy(DisciplineFixtures.policy(Map.of(
                ThresholdKeys.PRODUCTION_TIME_LIMIT_BLOCK_ON_EXCEED, "1")));
    }

    private void port(String sku, String kind, Map<String, Object> config) {
        WorkflowTaskPort port = new WorkflowTaskPort();
        port.setFactoryId(F);
        port.setTaskId(TASK);
        port.setWorkflowPortId("in-" + sku);
        port.setDirection(WorkflowTaskPort.Direction.INPUT);
        port.setMaterialNodeId("node-" + sku);
        port.setMaterialKind(kind);
        port.setSkuId(sku);
        port.setTimeLimitConfig(config);
        when(portRepo.findByFactoryIdAndWorkflowInstanceId(F, INSTANCE)).thenReturn(List.of(port));
    }

    private static Map<String, Object> limit(int minutes, String start) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxMinutes", minutes);
        config.put("startEvent", start);
        return config;
    }

    /** 120 分钟时限的半成品, T0 入账 100kg; now = T0 + 155 ⇒ 已超时 35 分钟。 */
    private SemiFinishedInventory expiredSemi() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(11L)).thenReturn(List.of(
                SemiFinishedInventoryTransaction.builder().txnType("IN").reportId(1L)
                        .quantity(BigDecimal.valueOf(100)).createdAt(T0).build()));
        now = T0.plusMinutes(155);
        return SemiFinishedInventory.builder().id(11L).factoryId(F).intermediateBatchNo("W-1").productTypeId("SKU")
                .producedQuantity(BigDecimal.valueOf(100)).consumedQuantity(BigDecimal.ZERO)
                .unit("kg").createdAt(T0).build();
    }

    /** 解冻起算的原料, 没登记开始解冻。 */
    private MaterialBatch unthawedRaw() {
        port("RAW", "RAW_MATERIAL", limit(240, "THAW_MARKED"));
        MaterialBatch batch = new MaterialBatch();
        batch.setId("A");
        batch.setFactoryId(F);
        batch.setBatchNumber("MB-A");
        batch.setMaterialTypeId("RAW");
        batch.setCreatedAt(T0);
        batch.setReceiptQuantity(BigDecimal.TEN);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        when(materialRepo.findByIdAndFactoryId("A", F)).thenReturn(Optional.of(batch));
        when(edgeRepo.findByFactoryIdAndTargetIdAndTargetType(F, "A", LineageEdgeRecorder.NODE_MATERIAL_BATCH))
                .thenReturn(List.of());
        return batch;
    }

    // ------------------------------------------------------------------ 超时

    @Test
    @DisplayName("默认档: 超时投料成功, 写 1 条 OPEN 留痕, 返回 1 条警告 (说清超时多久、去哪复盘处置)")
    void defaultWarnOnly_expiredFeedPassesWithOneEventAndWarning() {
        SemiFinishedInventory semi = expiredSemi();
        List<String> warnings = service.assertSemiFeedForTask(F, task, semi, BigDecimal.TEN, 7L);
        System.out.println("[PROTO-Q41] default warnings=" + warnings);

        assertEquals(1, warnings.size(), "默认档必须带回 1 条警告: " + warnings);
        assertTrue(warnings.get(0).contains("W-1") && warnings.get(0).contains("已超时 35 分钟"), warnings.get(0));
        assertTrue(warnings.get(0).contains("供复盘"), warnings.get(0));
        assertFalse(warnings.get(0).contains("不能领用"), "只警告档的话不能再说「不能领用」: " + warnings.get(0));

        ArgumentCaptor<ProcessTimeLimitEvent> event = ArgumentCaptor.forClass(ProcessTimeLimitEvent.class);
        verify(writer, times(1)).openIfAbsent(event.capture());
        assertEquals(ProcessTimeLimitEvent.Status.OPEN, event.getValue().getStatus());
        assertEquals("11", event.getValue().getTargetId());
        assertEquals(7L, event.getValue().getBlockedBy());
        assertEquals(T0.plusMinutes(120), event.getValue().getDeadlineAt());
    }

    @Test
    @DisplayName("阴性对照: 工厂开了超时拦截 (=1) → 同一份料 409 BLOCKING, 留痕同样写 1 条")
    void blockOn_expiredFeedIs409AndEventWritten() {
        blockOn();
        SemiFinishedInventory semi = expiredSemi();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.assertSemiFeedForTask(F, task, semi, BigDecimal.TEN, 7L));
        assertEquals(409, e.getCode());
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, e.getErrorCode());
        assertEquals("BLOCKING", e.getSeverity());
        assertTrue(e.getMessage().contains("不能领用"), e.getMessage());
        verify(writer, times(1)).openIfAbsent(any());
    }

    @Test
    @DisplayName("开关读法: 只有显式 1 才拦; 0 / 2 / 没配都是只警告")
    void onlyExplicitOneBlocks() {
        for (String v : List.of("0", "2")) {
            service.setDisciplinePolicy(DisciplineFixtures.policy(Map.of(
                    ThresholdKeys.PRODUCTION_TIME_LIMIT_BLOCK_ON_EXCEED, v)));
            SemiFinishedInventory semi = expiredSemi();
            assertEquals(1, service.assertSemiFeedForTask(F, task, semi, BigDecimal.TEN, 7L).size(), "值=" + v);
        }
        assertEquals("production.time_limit.block_on_exceed", ThresholdKeys.PRODUCTION_TIME_LIMIT_BLOCK_ON_EXCEED);
        assertTrue(ThresholdKeys.isGating(ThresholdKeys.PRODUCTION_TIME_LIMIT_BLOCK_ON_EXCEED), "挡投料的开关是闸门类");
    }

    // ------------------------------------------------------------------ 未登记开始解冻

    @Test
    @DisplayName("默认档: 未登记开始解冻也照常投料, 带警告要求补登; 起算点不知道, 不写超时留痕")
    void defaultWarnOnly_startMissingPassesWithWarning() {
        MaterialBatch batch = unthawedRaw();
        List<String> warnings = service.assertRawFeedForTask(F, task, batch, null, 7L);
        System.out.println("[PROTO-Q41] start-missing warnings=" + warnings);
        assertEquals(1, warnings.size(), "" + warnings);
        assertTrue(warnings.get(0).contains("还没有登记开始解冻") && warnings.get(0).contains("补登"), warnings.get(0));
        verify(writer, never()).openIfAbsent(any());
    }

    @Test
    @DisplayName("阴性对照: 开了超时拦截 → 未登记开始解冻 409 START_MISSING")
    void blockOn_startMissingIs409() {
        blockOn();
        MaterialBatch batch = unthawedRaw();
        BusinessException e = assertThrows(BusinessException.class,
                () -> service.assertRawFeedForTask(F, task, batch, null, 7L));
        assertEquals(409, e.getCode());
        assertEquals(ProcessTimeLimitService.CODE_START_MISSING, e.getErrorCode());
    }

    // ------------------------------------------------------------------ 页面状态同口径

    @Test
    @DisplayName("页面状态带开关: 默认档 blockOnExceed=false 且文案不说「不能领用」; 开了 =true 且说「不能领用」")
    void statusCarriesTheSwitch() {
        SemiFinishedInventory semi = expiredSemi();
        when(semiRepo.findAvailableByFactory(F)).thenReturn(List.of(semi));
        when(materialRepo.findWipBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.AVAILABLE)).thenReturn(List.of());

        ProcessTimeLimitService.SourceStatus warn = service.statusForTask(F, TASK).get(0);
        assertEquals(ProcessTimeLimitService.State.EXPIRED, warn.state());
        assertFalse(warn.blockOnExceed());
        assertFalse(warn.reason().contains("不能领用"), warn.reason());

        blockOn();
        ProcessTimeLimitService.SourceStatus block = service.statusForTask(F, TASK).get(0);
        assertTrue(block.blockOnExceed());
        assertTrue(block.reason().contains("不能领用"), block.reason());
    }

    // ------------------------------------------------------------------ 真实 MVC 链: 警告进成功响应

    /** 最小的投料入口: 和逐道录入 saveRow 一样调 assertWebFeed, 成功就回 ApiResponse.success。 */
    @RestController
    class FeedController {
        @PostMapping("/feed")
        ApiResponse<String> feed() {
            service.assertWebFeed(F, "PLAN", webRow(), null, 7L);
            return ApiResponse.success("saved");
        }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(BusinessException.class)
        org.springframework.http.ResponseEntity<ApiResponse<Void>> onBusiness(BusinessException e) {
            ApiResponse<Void> body = ApiResponse.error(e.getCode(), e.getMessage());
            body.setErrorCode(e.getErrorCode());
            return org.springframework.http.ResponseEntity.status(e.getCode()).body(body);
        }
    }

    private ProcessSheetRowRequest webRow() {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber("W-1");
        ref.setSemiFinished(true);
        ref.setFeedQuantityKg(BigDecimal.TEN);
        ref.setWorkflowPortId("in-SKU");
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setUnit("kg");
        req.setUpstreamSources(List.of(ref));
        return req;
    }

    private MockMvc webFeedMvc() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(5L);
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, "PLAN")).thenReturn(List.of(batch));
        ProductionWorkflowInstance instance = mock(ProductionWorkflowInstance.class);
        when(instance.getId()).thenReturn(INSTANCE);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(F, 5L)).thenReturn(Optional.of(instance));
        SemiFinishedInventory semi = expiredSemi();
        when(semiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "W-1")).thenReturn(Optional.of(semi));
        return MockMvcBuilders.standaloneSetup(new FeedController())
                .setControllerAdvice(new TimeLimitWarningResponseAdvice(), new Errors())
                .build();
    }

    @Test
    @DisplayName("逐道录入默认档: HTTP 200 success, 响应带 severity=WARNING / errorCode=TIME_LIMIT_WARNING / actionHint=警告")
    void webFeedWarningReachesTheSuccessResponse() throws Exception {
        MockMvc mvc = webFeedMvc();
        String body = mvc.perform(post("/feed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("saved"))
                .andExpect(jsonPath("$.severity").value("WARNING"))
                .andExpect(jsonPath("$.errorCode").value(TimeLimitWarnings.RESPONSE_CODE))
                .andExpect(jsonPath("$.actionHint", containsString("已超时 35 分钟")))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        System.out.println("[PROTO-Q41] web feed response=" + body);
        verify(writer, times(1)).openIfAbsent(any());
    }

    @Test
    @DisplayName("阴性对照: 开了超时拦截 → 同一请求 409, 不带 TIME_LIMIT_WARNING")
    void webFeedBlockedWhenSwitchOn() throws Exception {
        MockMvc mvc = webFeedMvc();
        blockOn();
        mvc.perform(post("/feed").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errorCode").value(ProcessTimeLimitService.CODE_EXCEEDED))
                .andExpect(jsonPath("$.actionHint").doesNotExist());
        verify(writer, times(1)).openIfAbsent(any());
    }

    @Test
    @DisplayName("拼装规则: 失败响应不动; 没有警告不动; 已有 actionHint 时追加")
    void adviceAttachRules() {
        ApiResponse<Void> failed = ApiResponse.error(409, "x");
        TimeLimitWarningResponseAdvice.attach(failed, List.of("w"));
        assertEquals(null, failed.getErrorCode());

        ApiResponse<String> quiet = ApiResponse.success("ok");
        TimeLimitWarningResponseAdvice.attach(quiet, List.of());
        assertEquals(null, quiet.getSeverity());

        ApiResponse<String> hinted = ApiResponse.success("ok");
        hinted.setActionHint("先前的提示");
        TimeLimitWarningResponseAdvice.attach(hinted, List.of("w1", "w2"));
        assertEquals("先前的提示\nw1\nw2", hinted.getActionHint());
        assertEquals("WARNING", hinted.getSeverity());
    }
}
