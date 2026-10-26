package com.cretas.aims.service.foodsafety.ccp;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.dto.processentry.ProcessSheetRowResult;
import com.cretas.aims.dto.workflow.WorkflowClerkSheetConfigDTO;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.foodsafety.HaccpCheckpoint;
import com.cretas.aims.entity.foodsafety.HaccpMonitoringRecord;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.foodsafety.HaccpCheckpointRepository;
import com.cretas.aims.repository.foodsafety.HaccpMonitoringRecordRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.processentry.ProcessSheetService;
import com.cretas.aims.service.quality.release.QcDeviationHold;
import com.cretas.aims.service.workflow.WorkflowClerkSheetService;
import com.cretas.aims.service.yield.YieldReportService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 缺口 13 审查中 7 —— CCP 的服务端兜底: 报工与 CCP 实测值同一事务 ({@link CcpReportFacade})。
 *
 * <p>选的方案 (比「缺 CCP 时扣待检」更严): 挂了启用 CCP 的工序, 有产出的报工<b>必须</b>带齐实测值,
 * 缺一项 400 {@code CCP_READING_MISSING} 且报工服务根本不被调用 (产出不入账); 带齐了 ⇒ 报工落库后同事务落 CCP 记录,
 * 超限 ⇒ 当场扣留 (此时这笔产出已入账, 扣留覆盖它)。
 *
 * <p>CCP 判定用的是真的 {@link CcpMonitoringService} (只 mock 仓储层), 不是桩出来的「缺值异常」。
 * 阴性对照: 没挂 CCP 的工序、不产出的报工 (领料) 不带实测值照常透传。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("CCP 服务端兜底: 报工带实测值同一事务落库, 缺值整笔拒绝")
class CcpReportFacadeContractTest {

    private static final String F = "F-CRF";
    private static final Long BATCH_ID = 11L;
    private static final Long TASK_ID = 501L;
    private static final String WP_CCP = "WP-COOK";
    private static final String WP_PLAIN = "WP-PACK";

    @Mock private YieldReportService yieldReportService;
    @Mock private ProcessSheetService processSheetService;
    @Mock private WorkProcessTaskRepository taskRepository;
    @Mock private WorkflowClerkSheetService workflowClerkSheetService;
    @Mock private HaccpCheckpointRepository checkpointRepo;
    @Mock private HaccpMonitoringRecordRepository recordRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsRepo;
    @Mock private QcDeviationHold deviationHold;

    private CcpReportFacade facade;
    private final List<HaccpMonitoringRecord> saved = new ArrayList<>();

    private static HaccpCheckpoint cp() {
        return HaccpCheckpoint.builder().id(71L).factoryId(F).checkpointCode("CCP-T").name("中心温度")
                .hazardType("BIOLOGICAL").criticalLimitMin(new BigDecimal("75")).criticalLimitMax(new BigDecimal("100"))
                .unit("℃").workProcessId(WP_CCP).active(true).build();
    }

    @BeforeEach
    void setUp() {
        CcpMonitoringService ccp = new CcpMonitoringService(checkpointRepo, recordRepo, productionBatchRepo,
                finishedGoodsRepo, deviationHold);
        facade = new CcpReportFacade(yieldReportService, processSheetService, ccp, taskRepository,
                workflowClerkSheetService);
        when(checkpointRepo.findByFactoryIdAndWorkProcessIdAndActiveTrueOrderByCheckpointCodeAsc(F, WP_CCP))
                .thenReturn(List.of(cp()));
        when(checkpointRepo.findByFactoryIdAndWorkProcessIdAndActiveTrueOrderByCheckpointCodeAsc(F, WP_PLAIN))
                .thenReturn(List.of());
        when(checkpointRepo.findAllById(any())).thenReturn(List.of(cp()));
        when(recordRepo.save(any(HaccpMonitoringRecord.class))).thenAnswer(inv -> {
            HaccpMonitoringRecord r = inv.getArgument(0);
            saved.add(r);
            return r;
        });
        // 按凭据查重: 查的是真落过的记录 (内存), 不是恒空的桩 —— 否则「凭据撞了被当重放」测不出来
        when(recordRepo.findByFactoryIdAndReportRefAndCheckpointId(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> saved.stream()
                        .filter(r -> inv.getArgument(1).equals(r.getReportRef())
                                && inv.getArgument(2).equals(r.getCheckpointId()))
                        .toList());
        ProductionBatch pb = new ProductionBatch();
        pb.setId(BATCH_ID);
        pb.setFactoryId(F);
        pb.setBatchNumber("PB-11");
        pb.setProductionPlanId("PLAN-1");
        when(productionBatchRepo.findByIdAndFactoryId(BATCH_ID, F)).thenReturn(Optional.of(pb));
        when(deviationHold.holdForDeviation(any(), any(), any(), any()))
                .thenReturn(new QcDeviationHold.HoldResult(0, 1, 0));
    }

    private static CcpMonitoringService.Reading reading(String v) {
        return new CcpMonitoringService.Reading(71L, new BigDecimal(v), null);
    }

    @Nested
    @DisplayName("App 逐道报工")
    class App {

        private void stepIs(String workProcessId) {
            WorkProcessTask t = WorkProcessTask.builder().id(TASK_ID).factoryId(F).productionBatchId(BATCH_ID)
                    .workProcessId(workProcessId).processOrder(2).build();
            when(taskRepository.findByFactoryIdAndId(F, TASK_ID)).thenReturn(Optional.of(t));
        }

        private YieldReportRequest output(List<CcpMonitoringService.Reading> readings) {
            YieldReportRequest r = new YieldReportRequest();
            r.setWorkProcessTaskId(TASK_ID);
            r.setReportKind("OUTPUT");
            r.setOutputQuantity(new BigDecimal("40"));
            r.setCcpReadings(readings);
            return r;
        }

        private void reportReturns(long reportId) {
            Map<String, Object> out = new HashMap<>();
            out.put("reportId", reportId);
            when(yieldReportService.submitReport(eq(F), eq(BATCH_ID), anyLong(), any())).thenReturn(out);
        }

        @Test
        @DisplayName("挂 CCP 的工序报产出不带实测值 → 400 CCP_READING_MISSING, 报工服务没被调用 (产出没入账)")
        void missingReadingsRejectBeforeAnyWrite() {
            stepIs(WP_CCP);
            assertThatThrownBy(() -> facade.submitYieldReport(F, BATCH_ID, 9L, "工人", output(null)))
                    .isInstanceOfSatisfying(BusinessException.class, e -> {
                        assertThat(e.getErrorCode()).isEqualTo(CcpMonitoringService.CODE_MISSING);
                        assertThat(e.getMessage()).contains("中心温度");
                    });
            verify(yieldReportService, never()).submitReport(any(), any(), any(), any());
            verify(recordRepo, never()).save(any());
        }

        @Test
        @DisplayName("带齐且在限值内 → 先入账再记 CCP (报工凭据 YR:报工id), 不扣留")
        void inLimitRecordsWithReportRef() {
            stepIs(WP_CCP);
            reportReturns(9001L);
            Map<String, Object> res = facade.submitYieldReport(F, BATCH_ID, 9L, "工人", output(List.of(reading("80"))));
            System.out.println("FACADE_APP_RESULT=" + res);
            assertThat(saved).singleElement().satisfies(r -> {
                assertThat(r.getReportRef()).isEqualTo("YR:9001");
                assertThat(r.getBatchNumber()).isEqualTo("PB-11");
                assertThat(r.isDeviation()).isFalse();
            });
            @SuppressWarnings("unchecked")
            Map<String, Object> ccp = (Map<String, Object>) res.get("ccp");
            assertThat(ccp.get("deviationCount")).isEqualTo(0);
            verify(deviationHold, never()).holdForDeviation(any(), any(), any(), any());
        }

        @Test
        @DisplayName("超限 → 报工入账之后 (同一事务) 扣留这一批/计划, 结果带偏离提示")
        void outOfLimitHoldsAfterTheOutputIsPosted() {
            stepIs(WP_CCP);
            reportReturns(9002L);
            Map<String, Object> res = facade.submitYieldReport(F, BATCH_ID, 9L, "工人", output(List.of(reading("70"))));
            InOrder order = inOrder(yieldReportService, deviationHold);
            order.verify(yieldReportService).submitReport(eq(F), eq(BATCH_ID), eq(9L), any());
            order.verify(deviationHold).holdForDeviation(F, "PB-11", BATCH_ID, "PLAN-1");
            @SuppressWarnings("unchecked")
            Map<String, Object> ccp = (Map<String, Object>) res.get("ccp");
            assertThat(ccp.get("deviationCount")).isEqualTo(1);
            assertThat((String) ccp.get("message")).contains("待检");
        }

        @Test
        @DisplayName("幂等重放 (同一报工凭据已记过) → 不重复记")
        void replayDoesNotRecordTwice() {
            stepIs(WP_CCP);
            reportReturns(9003L);
            HaccpMonitoringRecord existing = HaccpMonitoringRecord.builder().id(1L).factoryId(F).checkpointId(71L)
                    .batchNumber("PB-11").measuredValue(new BigDecimal("80")).isDeviation(false)
                    .reportRef("YR:9003").build();
            when(recordRepo.findByFactoryIdAndReportRefAndCheckpointId(F, "YR:9003", 71L)).thenReturn(List.of(existing));
            facade.submitYieldReport(F, BATCH_ID, 9L, "工人", output(List.of(reading("80"))));
            verify(recordRepo, never()).save(any());
        }

        @Test
        @DisplayName("阴性对照: 没挂 CCP 的工序报产出, 不带实测值 → 原样透传, 不记 CCP")
        void plainStepPassesThrough() {
            stepIs(WP_PLAIN);
            reportReturns(9004L);
            Map<String, Object> res = facade.submitYieldReport(F, BATCH_ID, 9L, "工人", output(null));
            assertThat(res).containsEntry("reportId", 9004L).doesNotContainKey("ccp");
            verify(recordRepo, never()).save(any());
        }

        @Test
        @DisplayName("阴性对照: 挂 CCP 的工序报领料 (INPUT, 无产出) → 不要求实测值")
        void inputReportNeedsNoReadings() {
            stepIs(WP_CCP);
            reportReturns(9005L);
            YieldReportRequest r = new YieldReportRequest();
            r.setWorkProcessTaskId(TASK_ID);
            r.setReportKind("INPUT");
            r.setInputQuantity(new BigDecimal("50"));
            facade.submitYieldReport(F, BATCH_ID, 9L, "工人", r);
            verify(yieldReportService).submitReport(eq(F), eq(BATCH_ID), eq(9L), any());
            verify(recordRepo, never()).save(any());
        }
    }

    @Nested
    @DisplayName("web 逐工序表正式提交")
    class Web {

        private void sheetConfig(Integer order, String workProcessId) {
            WorkflowClerkSheetConfigDTO.ProcessDescriptor d = WorkflowClerkSheetConfigDTO.ProcessDescriptor.builder()
                    .processOrder(order).workProcessId(workProcessId).build();
            when(workflowClerkSheetService.getWorkflowSheetConfig(F, "PLAN-1"))
                    .thenReturn(WorkflowClerkSheetConfigDTO.builder().processes(List.of(d)).build());
        }

        private ProcessSheetRowRequest row(List<CcpMonitoringService.Reading> readings) {
            ProcessSheetRowRequest r = new ProcessSheetRowRequest();
            r.setClientRowId("row-1");
            r.setIdempotencyKey("idem-1");
            r.setProcessOrder(2);
            r.setOutputQuantity(new BigDecimal("40"));
            r.setCcpReadings(readings);
            return r;
        }

        @Test
        @DisplayName("挂 CCP 的工序正式提交不带实测值 → 400, 报工没被调用")
        void missingReadingsRejected() {
            sheetConfig(2, WP_CCP);
            assertThatThrownBy(() -> facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", row(null)))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CcpMonitoringService.CODE_MISSING));
            verify(processSheetService, never()).submitRow(any(), any(), any(), any());
        }

        @Test
        @DisplayName("超限 → 报工返回的提示里带 CCP 偏离; 记录挂在本行批号上, 凭据含计划/行/幂等号")
        void deviationIsAppendedToWarnings() {
            sheetConfig(2, WP_CCP);
            ProcessSheetRowResult result = new ProcessSheetRowResult();
            result.setClientRowId("row-1");
            result.setBatchId(BATCH_ID);
            result.setBatchNumber("PB-11");
            result.setMaterialized(true);
            result.setWarnings(List.of("原有提示"));
            when(processSheetService.submitRow(eq(F), eq("PLAN-1"), any(), eq(9L))).thenReturn(result);
            ProcessSheetRowResult out = facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", row(List.of(reading("70"))));
            assertThat(out.getWarnings()).hasSize(2).first().isEqualTo("原有提示");
            assertThat(out.getWarnings().get(1)).contains("超出限值");
            ArgumentCaptor<HaccpMonitoringRecord> rec = ArgumentCaptor.forClass(HaccpMonitoringRecord.class);
            verify(recordRepo).save(rec.capture());
            assertThat(rec.getValue().getReportRef()).isEqualTo("PS:PLAN-1:row-1:idem-1");
            assertThat(rec.getValue().getBatchNumber()).isEqualTo("PB-11");
            assertThat(rec.getValue().getSource()).isEqualTo(CcpMonitoringService.SOURCE_WEB);
        }

        @Test
        @DisplayName("第二轮审查 H1: 同一行「提交(限内) → 撤销 → 再提交(超限)」, 第二次照记偏离并扣待检")
        void resubmitAfterUndoRecordsTheNewReading() {
            sheetConfig(2, WP_CCP);
            ProcessSheetRowResult result = new ProcessSheetRowResult();
            result.setClientRowId("row-1");
            result.setBatchId(BATCH_ID);
            result.setBatchNumber("PB-11");
            when(processSheetService.submitRow(eq(F), eq("PLAN-1"), any(), eq(9L))).thenReturn(result);
            ProcessSheetRowRequest first = row(List.of(reading("80")));
            first.setIdempotencyKey(null);                 // web 不发幂等号
            facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", first);
            // ……撤销小结: 行退回草稿, clientRowId 不变……
            ProcessSheetRowRequest second = row(List.of(reading("70")));
            second.setIdempotencyKey(null);
            facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", second);
            System.out.println("H1 refs=" + saved.stream().map(HaccpMonitoringRecord::getReportRef).toList());
            assertThat(saved).hasSize(2);
            assertThat(saved.get(0).getReportRef()).isNotEqualTo(saved.get(1).getReportRef());
            assertThat(saved.get(1).isDeviation()).isTrue();
            verify(deviationHold).holdForDeviation(F, "PB-11", BATCH_ID, "PLAN-1");
        }

        @Test
        @DisplayName("真正的重放仍然挡住: 已提交的行再提交被 submitRow 409 拒绝, 不落第二条 CCP")
        void replayIsStillRejected() {
            sheetConfig(2, WP_CCP);
            ProcessSheetRowResult result = new ProcessSheetRowResult();
            result.setBatchId(BATCH_ID);
            result.setBatchNumber("PB-11");
            when(processSheetService.submitRow(eq(F), eq("PLAN-1"), any(), eq(9L)))
                    .thenReturn(result)
                    .thenThrow(new BusinessException(409, "该报工行已经正式提交")
                            .withCode("PROCESS_SHEET_ROW_ALREADY_SUBMITTED"));
            ProcessSheetRowRequest req = row(List.of(reading("70")));
            req.setIdempotencyKey(null);
            facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", req);
            assertThatThrownBy(() -> facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", req))
                    .isInstanceOfSatisfying(BusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo("PROCESS_SHEET_ROW_ALREADY_SUBMITTED"));
            assertThat(saved).hasSize(1);
        }

        @Test
        @DisplayName("阴性对照: legacy 计划 (没有 Workflow 录入配置) → 不要求实测值, 原样透传")
        void legacyPlanPassesThrough() {
            when(workflowClerkSheetService.getWorkflowSheetConfig(F, "PLAN-1")).thenReturn(null);
            ProcessSheetRowResult result = new ProcessSheetRowResult();
            result.setClientRowId("row-1");
            when(processSheetService.submitRow(eq(F), eq("PLAN-1"), any(), eq(9L))).thenReturn(result);
            assertThat(facade.submitProcessSheetRow(F, "PLAN-1", 9L, "文员", row(null))).isSameAs(result);
            verify(recordRepo, never()).save(any());
        }
    }
}
