package com.cretas.aims.service.foodsafety.timelimit;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.SemiFinishedInventoryTransaction;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.foodsafety.ProcessTimeLimitEvent;
import com.cretas.aims.entity.foodsafety.ProcessTimeMark;
import com.cretas.aims.entity.lineage.BatchLineageEdge;
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
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.quality.release.QcReleaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 缺口 14 的判定: 配时限 → 未超时可领 → 超时被拦 → 处置放行 → 拆批混批没重置。 */
class ProcessTimeLimitServiceContractTest {

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
    private final QcReleaseService qcRelease = mock(QcReleaseService.class);
    private final ProductionPlanRepository planRepo = mock(ProductionPlanRepository.class);
    private final com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository revisionRepo =
            mock(com.cretas.aims.repository.ProductProcessWorkflowRevisionRepository.class);

    private LocalDateTime now = T0;
    private final List<ProcessTimeLimitEvent> releases = new ArrayList<>();
    private ProcessTimeLimitService service;
    private WorkProcessTask task;

    @BeforeEach
    void setUp() {
        service = new ProcessTimeLimitService(portRepo, taskRepo, mock(WorkProcessRepository.class), batchRepo,
                planRepo, instanceRepo, reportRepo, semiRepo, txnRepo, materialRepo,
                mock(FinishedGoodsBatchRepository.class), edgeRepo, markRepo, eventRepo, writer, qcRelease,
                mock(WarehouseResolver.class), revisionRepo, new com.fasterxml.jackson.databind.ObjectMapper()) {
            @Override
            protected LocalDateTime now() {
                return now;
            }
        };
        task = new WorkProcessTask();
        task.setId(TASK);
        task.setFactoryId(F);
        task.setWorkflowInstanceId(INSTANCE);
        task.setProcessOrder(2);
        when(taskRepo.findByFactoryIdAndId(F, TASK)).thenReturn(Optional.of(task));
        when(eventRepo.findByFactoryIdAndTargetTypeAndTargetIdAndStatus(eq(F), anyString(), anyString(),
                eq(ProcessTimeLimitEvent.Status.RELEASED))).thenReturn(releases);
        when(markRepo.findByTargets(eq(F), anyString(), any())).thenReturn(List.of());
    }

    private WorkflowTaskPort port(String sku, String kind, Map<String, Object> config) {
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
        return port;
    }

    private static Map<String, Object> limit(int minutes, String start, Object... extra) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("maxMinutes", minutes);
        config.put("startEvent", start);
        for (int i = 0; i < extra.length; i += 2) {
            config.put((String) extra[i], extra[i + 1]);
        }
        return config;
    }

    private SemiFinishedInventory semi(double produced, double consumed) {
        return SemiFinishedInventory.builder().id(11L).factoryId(F).intermediateBatchNo("W-1").productTypeId("SKU")
                .producedQuantity(BigDecimal.valueOf(produced)).consumedQuantity(BigDecimal.valueOf(consumed))
                .unit("kg").createdAt(T0).build();
    }

    private static SemiFinishedInventoryTransaction txn(String type, Long reportId, double qty, int minute) {
        return SemiFinishedInventoryTransaction.builder().txnType(type).reportId(reportId)
                .quantity(BigDecimal.valueOf(qty)).createdAt(T0.plusMinutes(minute)).build();
    }

    private void ledger(SemiFinishedInventoryTransaction... txns) {
        when(txnRepo.findBySemiFinishedIdOrderByCreatedAtAsc(11L)).thenReturn(List.of(txns));
    }

    private BusinessException blocked(SemiFinishedInventory semi, double quantity) {
        return assertThrows(BusinessException.class, () -> service.assertSemiFeedForTask(
                F, task, semi, BigDecimal.valueOf(quantity), 7L));
    }

    private void passes(SemiFinishedInventory semi, double quantity) {
        assertDoesNotThrow(() -> service.assertSemiFeedForTask(F, task, semi, BigDecimal.valueOf(quantity), 7L));
    }

    private ProcessTimeLimitEvent release(LocalDateTime coveredUntil, LocalDateTime validUntil) {
        ProcessTimeLimitEvent event = ProcessTimeLimitEvent.builder().coveredUntil(coveredUntil)
                .workflowPortId("in-SKU").validUntil(validUntil).status(ProcessTimeLimitEvent.Status.RELEASED).build();
        releases.add(event);
        return event;
    }

    @Test
    @DisplayName("没配时限的端口: 不读流水, 不写留痕")
    void unconfiguredPortIsUntouched() {
        port("SKU", "SEMI_FINISHED", null);
        now = T0.plusYears(1);
        passes(semi(100, 0), 10);
        verify(txnRepo, never()).findBySemiFinishedIdOrderByCreatedAtAsc(anyLong());
        verify(writer, never()).openIfAbsent(any());
    }

    @Test
    @DisplayName("没有工艺快照的旧任务: 不限时")
    void legacyTaskWithoutWorkflowIsUntouched() {
        task.setWorkflowInstanceId(null);
        now = T0.plusYears(1);
        passes(semi(100, 0), 10);
        verify(portRepo, never()).findByFactoryIdAndWorkflowInstanceId(anyString(), anyLong());
    }

    @Test
    @DisplayName("未超时可领, 超时 409: 错误码、措辞、留痕都对; 恰好到点仍可领")
    void withinLimitPassesAndExpiredIsBlockedWithTrace() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        SemiFinishedInventory semi = semi(100, 0);

        now = T0.plusMinutes(119);
        passes(semi, 10);
        now = T0.plusMinutes(120);
        passes(semi, 10);
        verify(writer, never()).openIfAbsent(any());

        now = T0.plusMinutes(155);
        BusinessException e = blocked(semi, 10);
        assertEquals(409, e.getCode());
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, e.getErrorCode());
        assertTrue(e.getMessage().contains("W-1") && e.getMessage().contains("已超时 35 分钟"), e.getMessage());

        ArgumentCaptor<ProcessTimeLimitEvent> event = ArgumentCaptor.forClass(ProcessTimeLimitEvent.class);
        verify(writer).openIfAbsent(event.capture());
        assertEquals(ProcessTimeLimitEvent.Status.OPEN, event.getValue().getStatus());
        assertEquals("11", event.getValue().getTargetId());
        assertEquals(T0.plusMinutes(120), event.getValue().getDeadlineAt());
        assertEquals(0, BigDecimal.valueOf(100).compareTo(event.getValue().getExpiredQuantity()));
    }

    @Test
    @DisplayName("刚过期: 写「不到 1 分钟」, 不写「已超时 0 分钟」")
    void justExpiredMessageIsNotZeroMinutes() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        now = T0.plusMinutes(120).plusSeconds(20);
        String message = blocked(semi(100, 0), 10).getMessage();
        assertTrue(message.contains("已超时 不到 1 分钟"), message);
    }

    @Test
    @DisplayName("留痕写失败也照样拦")
    void traceFailureStillBlocks() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        org.mockito.Mockito.doThrow(new IllegalStateException("db")).when(writer).openIfAbsent(any());
        now = T0.plusMinutes(200);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("拆批: 领走一部分后剩下的不重新计时")
    void partialDrawDoesNotResetClock() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0), txn("OUT", null, -40, 100));
        now = T0.plusMinutes(130);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 40), 10).getErrorCode());
    }

    @Test
    @DisplayName("混批取最早: 新料进来也不能把旧料洗新, 整行被拦")
    void earliestRuleBlocksWholeRowAfterFreshLotJoins() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 50, 0), txn("IN", 2L, 50, 110));
        now = T0.plusMinutes(130);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 1).getErrorCode());
    }

    @Test
    @DisplayName("按笔: 未超时的部分能领, 超过就拦并说清可领多少")
    void perLotAllowsFreshQuantityOnly() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "mixRule", "PER_LOT"));
        ledger(txn("IN", 1L, 50, 0), txn("IN", 2L, 50, 110));
        SemiFinishedInventory semi = semi(100, 0);
        now = T0.plusMinutes(130);
        passes(semi, 50);
        BusinessException e = blocked(semi, 50.5);
        assertTrue(e.getMessage().contains("现在只能领 50"), e.getMessage());
    }

    @Test
    @DisplayName("按笔: 新料被领走后, 剩下的旧料仍被认作超时")
    void perLotRemainderAfterFreshDrawIsStillOld() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "mixRule", "PER_LOT"));
        ledger(txn("IN", 1L, 50, 0), txn("IN", 2L, 50, 110), txn("OUT", null, -50, 125));
        now = T0.plusMinutes(130);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 50), 1).getErrorCode());
    }

    @Test
    @DisplayName("复检放行后可领; 之后新入的料不沾光")
    void releaseCoversOnlyLotsAtEventTime() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        now = T0.plusMinutes(200);
        release(now, null);
        passes(semi(100, 0), 10);

        ledger(txn("IN", 1L, 100, 0), txn("IN", 2L, 50, 210));
        now = T0.plusMinutes(400);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(150, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("放行只算同一端口: 宽松工序的结论不替严格工序放行")
    void releaseFromAnotherPortDoesNotApply() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        now = T0.plusMinutes(200);
        release(now, null).setWorkflowPortId("in-loose");
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("按笔: 已录入 / 待审批但账上没扣的量要算掉, 两行不能把超时的料领走")
    void perLotCountsClaimedButUndeductedQuantity() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "mixRule", "PER_LOT"));
        ledger(txn("IN", 1L, 50, 0), txn("IN", 2L, 50, 110));
        com.cretas.aims.service.yield.PendingInterimFeedService pending =
                mock(com.cretas.aims.service.yield.PendingInterimFeedService.class);
        when(pending.pendingSemiFeedSnapshot(F)).thenReturn(new com.cretas.aims.service.yield.PendingInterimFeedService
                .Snapshot(Map.of("W-1", BigDecimal.valueOf(30)), java.util.Set.of()));
        when(reportRepo.sumPendingInputBySourceWipNo(F, "W-1", null)).thenReturn(BigDecimal.TEN);
        service.setPendingInterimFeedService(pending);
        now = T0.plusMinutes(130);
        passes(semi(100, 0), 10);
        assertTrue(blocked(semi(100, 0), 11).getMessage().contains("现在只能领 10"));
    }

    @Test
    @DisplayName("二次加工开工在物化之前: 批次和实例都没有时读钉住的工艺版本")
    void planBeforeMaterializationReadsPinnedRevision() {
        com.cretas.aims.entity.ProductionPlan plan = mock(com.cretas.aims.entity.ProductionPlan.class);
        when(plan.getId()).thenReturn("PLAN");
        when(plan.getSelectedWorkflowRevisionId()).thenReturn(77L);
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, "PLAN")).thenReturn(List.of());
        when(planRepo.findByIdAndFactoryId("PLAN", F)).thenReturn(Optional.of(plan));
        com.cretas.aims.entity.ProductProcessWorkflowRevision revision =
                mock(com.cretas.aims.entity.ProductProcessWorkflowRevision.class);
        when(revision.getNodesJson()).thenReturn("[{\"id\":\"m\",\"kind\":\"SEMI_FINISHED\",\"data\":{\"skuId\":\"SKU\"}},"
                + "{\"id\":\"p\",\"kind\":\"PROCESS\",\"data\":{\"ports\":[{\"id\":\"in-SKU\",\"direction\":\"INPUT\","
                + "\"materialNodeId\":\"m\",\"timeLimit\":{\"maxMinutes\":120,\"startEvent\":\"OUTPUT_POSTED\"}}]}}]");
        when(revisionRepo.findByIdAndFactoryId(77L, F)).thenReturn(Optional.of(revision));
        ledger(txn("IN", 1L, 100, 0));
        now = T0.plusMinutes(200);
        BusinessException e = assertThrows(BusinessException.class, () -> service.assertSemiFeedForPlan(
                F, plan, semi(100, 0), BigDecimal.TEN, 7L));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, e.getErrorCode());
    }

    @Test
    @DisplayName("复检放行过了有效期: 又算超时")
    void releaseExpires() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        now = T0.plusMinutes(200);
        release(now, now.plusMinutes(30));
        passes(semi(100, 0), 10);
        now = T0.plusMinutes(231);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("上道报工起算: 取报工时刻, 审批入账晚不算延时")
    void reportedStartUsesReportTime() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_REPORTED"));
        ledger(txn("IN", 1L, 100, 60));
        ProductionReport report = ProductionReport.builder().id(1L).createdAt(T0).build();
        when(reportRepo.findAllById(any())).thenReturn(List.of(report));
        now = T0.plusMinutes(130);
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("上道完工起算: 没完工 = 未起算可领, 完工后才算")
    void upstreamCompletedStart() {
        port("SKU", "SEMI_FINISHED", limit(120, "UPSTREAM_COMPLETED"));
        ledger(txn("IN", 1L, 100, 0));
        ProductionReport report = ProductionReport.builder().id(1L).workProcessTaskId(2L).build();
        when(reportRepo.findAllById(any())).thenReturn(List.of(report));
        WorkProcessTask upstream = new WorkProcessTask();
        when(taskRepo.findByFactoryIdAndId(F, 2L)).thenReturn(Optional.of(upstream));
        now = T0.plusDays(2);
        passes(semi(100, 0), 10);

        upstream.setActualEndAt(T0.plusDays(1));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());
    }

    @Test
    @DisplayName("回料默认继承原批次开工时刻; 配了从回料产出起算才按入账; 可单设时限")
    void reworkRules() {
        SemiFinishedInventory rework = semi(100, 0);
        rework.setIsRework(true);
        rework.setBatchId(5L);
        ProductionBatch batch = new ProductionBatch();
        batch.setStartTime(T0.minusMinutes(300));
        when(batchRepo.findByIdAndFactoryId(5L, F)).thenReturn(Optional.of(batch));
        ledger(txn("IN", null, 100, 0));
        now = T0.plusMinutes(10);

        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(rework, 10).getErrorCode());

        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "reworkRule", "FROM_REWORK_OUTPUT"));
        passes(rework, 10);

        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "reworkRule", "FROM_REWORK_OUTPUT",
                "reworkMaxMinutes", 5));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(rework, 10).getErrorCode());
    }

    @Test
    @DisplayName("冷藏暂停: 配了才算, 没配登记了也不顺延")
    void coldPauseOnlyCountsWhenConfigured() {
        ledger(txn("IN", 1L, 100, 0));
        ProcessTimeMark pause = ProcessTimeMark.builder().id(41L).markKind(ProcessTimeMark.Kind.PAUSE)
                .markCode(ProcessTimeLimitConfig.PAUSE_COLD_STORAGE).startedAt(T0.plusMinutes(30)).build();
        when(markRepo.findByTargets(eq(F), eq("SEMI_FINISHED"), any())).thenReturn(List.of(pause));
        now = T0.plusMinutes(400);

        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, blocked(semi(100, 0), 10).getErrorCode());

        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "pauseCodes", List.of("COLD_STORAGE")));
        passes(semi(100, 0), 10);
    }

    private MaterialBatch raw(String id, String docType, LocalDateTime createdAt) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setFactoryId(F);
        batch.setBatchNumber("MB-" + id);
        batch.setMaterialTypeId("RAW");
        batch.setSourceDocType(docType);
        batch.setCreatedAt(createdAt);
        batch.setReceiptQuantity(BigDecimal.TEN);
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        when(materialRepo.findByIdAndFactoryId(id, F)).thenReturn(Optional.of(batch));
        when(edgeRepo.findByFactoryIdAndTargetIdAndTargetType(F, id, LineageEdgeRecorder.NODE_MATERIAL_BATCH))
                .thenReturn(List.of());
        return batch;
    }

    private void transferred(String parentId, String childId) {
        BatchLineageEdge edge = new BatchLineageEdge();
        edge.setEdgeType(LineageEdgeRecorder.EDGE_MATERIAL_TRANSFER);
        edge.setSourceId(parentId);
        when(edgeRepo.findByFactoryIdAndTargetIdAndTargetType(F, childId, LineageEdgeRecorder.NODE_MATERIAL_BATCH))
                .thenReturn(List.of(edge));
    }

    @Test
    @DisplayName("解冻起算: 没登记不猜, 拦下并要求登记")
    void thawStartMissingBlocks() {
        port("RAW", "RAW_MATERIAL", limit(240, "THAW_MARKED"));
        BusinessException e = assertThrows(BusinessException.class, () -> service.assertRawFeedForTask(
                F, task, raw("A", null, T0), null, 7L));
        assertEquals(ProcessTimeLimitService.CODE_START_MISSING, e.getErrorCode());
        verify(writer, never()).openIfAbsent(any());
    }

    @Test
    @DisplayName("拆批继承: 母批登记的解冻跟到调拨子批; 配成重新起算才不跟")
    void splitInheritsParentThaw() {
        MaterialBatch parent = raw("P", null, T0.minusDays(3));
        MaterialBatch child = raw("C", "TRANSFER_IN", T0.plusMinutes(200));
        transferred(parent.getId(), child.getId());
        ProcessTimeMark thaw = ProcessTimeMark.builder().markKind(ProcessTimeMark.Kind.START)
                .markCode(ProcessTimeLimitConfig.MARK_THAW).startedAt(T0).build();
        when(markRepo.findByTargets(F, "RAW_MATERIAL_BATCH", List.of("C", "P"))).thenReturn(List.of(thaw));
        now = T0.plusMinutes(250);

        port("RAW", "RAW_MATERIAL", limit(240, "THAW_MARKED"));
        BusinessException e = assertThrows(BusinessException.class, () -> service.assertRawFeedForTask(
                F, task, child, null, 7L));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, e.getErrorCode());

        port("RAW", "RAW_MATERIAL", limit(240, "THAW_MARKED", "splitRule", "RESTART_AT_SPLIT"));
        BusinessException restart = assertThrows(BusinessException.class, () -> service.assertRawFeedForTask(
                F, task, child, null, 7L));
        assertEquals(ProcessTimeLimitService.CODE_START_MISSING, restart.getErrorCode());
    }

    @Test
    @DisplayName("领到生产仓起算: 再次调拨不重置, 取最早一次")
    void outboundKeepsEarliestTransfer() {
        MaterialBatch first = raw("B1", "TRANSFER_IN", T0);
        MaterialBatch second = raw("B2", "TRANSFER_IN", T0.plusMinutes(100));
        transferred(first.getId(), second.getId());
        port("RAW", "RAW_MATERIAL", limit(120, "RAW_OUTBOUND"));
        now = T0.plusMinutes(130);
        assertThrows(BusinessException.class, () -> service.assertRawFeedForTask(F, task, second, null, 7L));
    }

    @Test
    @DisplayName("没领到生产仓 = 未起算, 不拦")
    void outboundWithoutTransferIsNotStarted() {
        port("RAW", "RAW_MATERIAL", limit(120, "RAW_OUTBOUND"));
        MaterialBatch batch = raw("A", null, T0);
        now = T0.plusDays(9);
        assertDoesNotThrow(() -> service.assertRawFeedForTask(F, task, batch, null, 7L));
    }

    private void webConsumer() {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(5L);
        when(batchRepo.findByFactoryIdAndProductionPlanId(F, "PLAN")).thenReturn(List.of(batch));
        ProductionWorkflowInstance instance = mock(ProductionWorkflowInstance.class);
        when(instance.getId()).thenReturn(INSTANCE);
        when(instanceRepo.findByFactoryIdAndProductionBatchId(F, 5L)).thenReturn(Optional.of(instance));
    }

    private ProcessSheetRowRequest webRow(double quantity) {
        ProcessSheetRowRequest.UpstreamRef ref = new ProcessSheetRowRequest.UpstreamRef();
        ref.setSourceBatchNumber("W-1");
        ref.setSemiFinished(true);
        ref.setFeedQuantityKg(BigDecimal.valueOf(quantity));
        ref.setWorkflowPortId("in-SKU");
        ProcessSheetRowRequest req = new ProcessSheetRowRequest();
        req.setUnit("kg");
        req.setUpstreamSources(List.of(ref));
        return req;
    }

    @Test
    @DisplayName("逐道录入: 超时被拦; 已保存的量重存不重判, 只判新增")
    void webFeedChecksOnlyNewQuantity() {
        webConsumer();
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED"));
        ledger(txn("IN", 1L, 100, 0));
        when(semiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "W-1"))
                .thenReturn(Optional.of(semi(100, 0)));
        now = T0.plusMinutes(200);

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.assertWebFeed(F, "PLAN", webRow(10), null, 7L));
        assertEquals(ProcessTimeLimitService.CODE_EXCEEDED, e.getErrorCode());

        assertDoesNotThrow(() -> service.assertWebFeed(F, "PLAN", webRow(10), webRow(10), 7L));
        assertThrows(BusinessException.class, () -> service.assertWebFeed(F, "PLAN", webRow(11), webRow(10), 7L));
    }

    @Test
    @DisplayName("按笔且单位折算不了: 不知道领多少, 有超时就拦")
    void perLotWithoutComparableUnitIsConservative() {
        webConsumer();
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "mixRule", "PER_LOT"));
        ledger(txn("IN", 1L, 50, 0), txn("IN", 2L, 50, 110));
        when(semiRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(F, "W-1"))
                .thenReturn(Optional.of(semi(100, 0)));
        now = T0.plusMinutes(130);
        ProcessSheetRowRequest req = webRow(10);
        req.setUnit("箱");
        assertThrows(BusinessException.class, () -> service.assertWebFeed(F, "PLAN", req, null, 7L));
    }

    @Test
    @DisplayName("页面状态与写入口同一判定: 临近 / 超时 / 暂无")
    void statusUsesSameEvaluation() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "warnMinutes", 30));
        ledger(txn("IN", 1L, 100, 0));
        when(semiRepo.findAvailableByFactory(F)).thenReturn(List.of(semi(100, 0)));
        when(materialRepo.findWipBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.AVAILABLE)).thenReturn(List.of());

        now = T0.plusMinutes(60);
        assertEquals(ProcessTimeLimitService.State.OK, service.statusForTask(F, TASK).get(0).state());
        now = T0.plusMinutes(100);
        ProcessTimeLimitService.SourceStatus warning = service.statusForTask(F, TASK).get(0);
        assertEquals(ProcessTimeLimitService.State.WARNING, warning.state());
        assertEquals(20L, warning.remainingMinutes());
        now = T0.plusMinutes(130);
        ProcessTimeLimitService.SourceStatus expired = service.statusForTask(F, TASK).get(0);
        assertEquals(ProcessTimeLimitService.State.EXPIRED, expired.state());
        assertEquals(-10L, expired.remainingMinutes());
        assertEquals(0, BigDecimal.ZERO.compareTo(expired.drawableQuantity()));
    }

    @Test
    @DisplayName("工序状态只算这道工序的端口: 别的工序的时限不影响这里")
    void statusIsScopedToTheTask() {
        WorkflowTaskPort other = port("SKU", "SEMI_FINISHED", limit(5, "OUTPUT_POSTED"));
        other.setTaskId(99L);
        now = T0.plusDays(1);
        assertTrue(service.statusForTask(F, TASK).isEmpty());
    }

    @Test
    @DisplayName("暂停中: 状态带登记 id, 刷新后还能结束")
    void statusCarriesOpenPauseMark() {
        port("SKU", "SEMI_FINISHED", limit(120, "OUTPUT_POSTED", "pauseCodes", List.of("COLD_STORAGE")));
        ledger(txn("IN", 1L, 100, 0));
        when(semiRepo.findAvailableByFactory(F)).thenReturn(List.of(semi(100, 0)));
        when(materialRepo.findWipBatchesByFactoryIdAndStatus(F, MaterialBatchStatus.AVAILABLE)).thenReturn(List.of());
        ProcessTimeMark pause = ProcessTimeMark.builder().id(42L).markKind(ProcessTimeMark.Kind.PAUSE)
                .markCode(ProcessTimeLimitConfig.PAUSE_COLD_STORAGE).startedAt(T0.plusMinutes(30)).build();
        when(markRepo.findByTargets(eq(F), eq("SEMI_FINISHED"), any())).thenReturn(List.of(pause));
        now = T0.plusMinutes(60);
        ProcessTimeLimitService.SourceStatus status = service.statusForTask(F, TASK).get(0);
        assertTrue(status.paused());
        assertEquals(42L, status.pauseMarkId());
    }

    private ProcessTimeLimitEvent openEvent(String type, String id, double quantity) {
        ProcessTimeLimitEvent event = ProcessTimeLimitEvent.builder().id(1L).factoryId(F).targetType(type).targetId(id)
                .batchNumber("W-1").expiredQuantity(BigDecimal.valueOf(quantity)).recheckValidMinutes(30)
                .status(ProcessTimeLimitEvent.Status.OPEN).build();
        when(eventRepo.findByIdAndFactoryIdForUpdate(1L, F)).thenReturn(Optional.of(event));
        when(eventRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        return event;
    }

    @Test
    @DisplayName("复检放行: 结论必填, 记人和时间, 算有效期, 不动库存")
    void releaseDecisionLeavesTrace() {
        ProcessTimeLimitEvent event = openEvent("SEMI_FINISHED", "11", 50);
        now = T0.plusMinutes(200);
        assertThrows(BusinessException.class, () -> service.decide(F, 1L,
                new ProcessTimeLimitService.DecisionCommand("RELEASED", "  "), 7L, "qc"));

        service.decide(F, 1L, new ProcessTimeLimitService.DecisionCommand("released", "感官复检合格"), 7L, "qc");
        assertEquals(ProcessTimeLimitEvent.Status.RELEASED, event.getStatus());
        assertEquals("感官复检合格", event.getConclusion());
        assertEquals(7L, event.getDecidedBy());
        assertEquals(now.plusMinutes(30), event.getValidUntil());
        verify(qcRelease, never()).writeOffSemi(any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("判不合格: 半成品按超时量转出, 带回放能认的前缀, 不超过余额")
    void rejectWritesOffExpiredQuantity() {
        openEvent("SEMI_FINISHED", "11", 50);
        SemiFinishedInventory semi = semi(100, 70);
        when(semiRepo.findByIdAndFactoryIdForUpdate(11L, F)).thenReturn(Optional.of(semi));
        service.decide(F, 1L, new ProcessTimeLimitService.DecisionCommand("REJECTED", "超时报废"), 7L, "qc");
        ArgumentCaptor<BigDecimal> quantity = ArgumentCaptor.forClass(BigDecimal.class);
        verify(qcRelease).writeOffSemi(eq(F), eq(semi), quantity.capture(), eq(7L),
                eq(ProcessClock.REJECT_REF_PREFIX + "1"));
        assertEquals(0, BigDecimal.valueOf(30).compareTo(quantity.getValue()));
    }

    @Test
    @DisplayName("判不合格: 批次型置不合格")
    void rejectMarksBatchDefective() {
        openEvent("WIP_MATERIAL_BATCH", "M1", 10);
        MaterialBatch batch = new MaterialBatch();
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        when(materialRepo.findByIdAndFactoryIdForUpdate("M1", F)).thenReturn(Optional.of(batch));
        service.decide(F, 1L, new ProcessTimeLimitService.DecisionCommand("REJECTED", "超时"), 7L, "qc");
        assertEquals(MaterialBatchStatus.DEFECTIVE, batch.getStatus());
    }

    @Test
    @DisplayName("处置过的不能再处置; 别的工厂查不到")
    void closedOrForeignEventIsRefused() {
        ProcessTimeLimitEvent event = openEvent("SEMI_FINISHED", "11", 50);
        event.setStatus(ProcessTimeLimitEvent.Status.RELEASED);
        BusinessException e = assertThrows(BusinessException.class, () -> service.decide(F, 1L,
                new ProcessTimeLimitService.DecisionCommand("REJECTED", "x"), 7L, "qc"));
        assertEquals(ProcessTimeLimitService.CODE_EVENT_CLOSED, e.getErrorCode());
        assertThrows(BusinessException.class, () -> service.decide("OTHER", 1L,
                new ProcessTimeLimitService.DecisionCommand("REJECTED", "x"), 7L, "qc"));
    }

    @Test
    @DisplayName("重复登记解冻返回第一条: 不能把起算点往后挪")
    void thawMarkIsIdempotent() {
        raw("A", null, T0);
        ProcessTimeMark first = ProcessTimeMark.builder().markKind(ProcessTimeMark.Kind.START)
                .markCode(ProcessTimeLimitConfig.MARK_THAW).startedAt(T0).build();
        when(markRepo.findByTargets(F, "RAW_MATERIAL_BATCH", List.of("A"))).thenReturn(List.of(first));
        ProcessTimeMark again = service.startMark(F, new ProcessTimeLimitService.MarkCommand(
                "RAW_MATERIAL_BATCH", "A", "START", "THAW", null, null, null), 7L, "op");
        assertEquals(T0, again.getStartedAt());
        verify(markRepo, never()).save(any());
    }

    @Test
    @DisplayName("别的工厂的批次不能登记; 暂停不能重复开; 登记时刻取服务端")
    void markScopeAndServerTime() {
        when(materialRepo.findByIdAndFactoryId("X", F)).thenReturn(Optional.empty());
        assertEquals(404, assertThrows(BusinessException.class, () -> service.startMark(F,
                new ProcessTimeLimitService.MarkCommand("RAW_MATERIAL_BATCH", "X", "START", "THAW", null, null, null), 7L, "op"))
                .getCode());

        raw("A", null, T0);
        when(markRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        now = T0.plusMinutes(5);
        ProcessTimeMark pause = service.startMark(F, new ProcessTimeLimitService.MarkCommand(
                "RAW_MATERIAL_BATCH", "A", "PAUSE", "COLD_STORAGE", null, null, null), 7L, "op");
        assertEquals(now, pause.getStartedAt());
        assertNull(pause.getEndedAt());

        when(markRepo.findByTargets(F, "RAW_MATERIAL_BATCH", List.of("A"))).thenReturn(List.of(pause));
        assertEquals(ProcessTimeLimitService.CODE_PAUSE_OPEN, assertThrows(BusinessException.class,
                () -> service.startMark(F, new ProcessTimeLimitService.MarkCommand(
                        "RAW_MATERIAL_BATCH", "A", "PAUSE", "COLD_STORAGE", null, null, null), 7L, "op")).getErrorCode());
    }
}
