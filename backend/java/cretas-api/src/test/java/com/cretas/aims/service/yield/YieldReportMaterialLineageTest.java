package com.cretas.aims.service.yield;

import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.MaterialInputRequest;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.SemiFinishedInventoryTransactionRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.lineage.LineageEdgeRecorder;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * App 报工领料 {@code material_batch_refs} → RAW_TO_PRODUCTION 边。
 *
 * <p>2026-10-24 prod 实测：近 60 天 14 个投入元素只有 1 个有边 —— 本类建带领料引用报工的两处
 * （{@code submitReport} / {@code recordMaterialInput}）都没调 recorder。两处各一条断言，
 * 删掉任何一处调用对应那条就红。装配沿用 {@code YieldReportServiceImplTest}。
 */
@DisplayName("App 报工领料写 RAW_TO_PRODUCTION 边 (两个提交入口)")
class YieldReportMaterialLineageTest {

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private FactorySettingsRepository factorySettingsRepo;
    private MaterialBatchRepository materialBatchRepo;
    private YieldReportServiceImpl svc;
    private LineageEdgeRecorder recorder;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        factorySettingsRepo = mock(FactorySettingsRepository.class);
        materialBatchRepo = mock(MaterialBatchRepository.class);
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        ProcessMaterialRecipeRepository recipeRepo = mock(ProcessMaterialRecipeRepository.class);
        ProductWorkProcessAssigneeRepository pwpAssigneeRepository = mock(ProductWorkProcessAssigneeRepository.class);
        com.cretas.aims.repository.ProductWorkProcessRepository productWorkProcessRepository =
                mock(com.cretas.aims.repository.ProductWorkProcessRepository.class);
        ProcessSheetRowRepository processSheetRowRepository = mock(ProcessSheetRowRepository.class);
        when(productWorkProcessRepository.findByFactoryIdAndId(anyString(), any())).thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(recipeRepo.findActiveByFactoryIdAndWorkProcessId(anyString(), anyString())).thenReturn(List.of());
        when(pwpAssigneeRepository.findByProductWorkProcessId(any())).thenReturn(List.of());
        when(processSheetRowRepository.findByFactoryIdAndPlanId(anyString(), anyString())).thenReturn(List.of());
        svc = new YieldReportServiceImpl(reportRepo, taskRepo, processRepo, new YieldCalculationServiceImpl(),
                mock(ProcessingService.class), factorySettingsRepo, materialBatchRepo,
                mock(ProductTypeRepository.class), mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class), wipRepo, mock(BatchLineageEdgeRepository.class),
                new ObjectMapper(), recipeRepo, mock(WipInventoryService.class), pwpAssigneeRepository,
                productWorkProcessRepository, new CostReconcileService(), processSheetRowRepository);
        ReflectionTestUtils.setField(svc, "wipTransactionRepository",
                mock(SemiFinishedInventoryTransactionRepository.class));
        ReflectionTestUtils.setField(svc, "workflowTaskPortRepository",
                mock(com.cretas.aims.repository.workflow.WorkflowTaskPortRepository.class));
        ReflectionTestUtils.setField(svc, "workshopReservationService",
                mock(com.cretas.aims.service.production.WorkshopReportReservationService.class));
        recorder = mock(LineageEdgeRecorder.class);
        ReflectionTestUtils.setField(svc, "lineageEdgeRecorder", recorder);

        MaterialBatch mb = new MaterialBatch();
        mb.setId("mb-ph");
        mb.setStatus(MaterialBatchStatus.AVAILABLE);
        mb.setUnitPrice(new BigDecimal("2.00"));
        mb.setReceiptQuantity(new BigDecimal("2000"));
        mb.setUsedQuantity(BigDecimal.ZERO);
        mb.setFactoryId("F006");
        when(materialBatchRepo.findById("mb-ph")).thenReturn(Optional.of(mb));
        when(materialBatchRepo.findByIdAndFactoryId("mb-ph", "F006")).thenReturn(Optional.of(mb));
        when(factorySettingsRepo.findProductionSettingsByFactoryId("F006")).thenReturn(null);
        when(reportRepo.findYieldReportsByBatch(anyString(), eq(1L))).thenReturn(List.of());
    }

    @Test
    @DisplayName("submitReport (INPUT 报工): 每个引用批次一条边, 键 report:<id>:material:<批次>, 同批多条引用求和")
    void submitReportWritesRawEdgePerReferencedBatch() {
        WorkProcessTask t = task(60L, "WP-PHASE");
        when(taskRepo.findByFactoryIdAndId("F006", 60L)).thenReturn(Optional.of(t));
        when(processRepo.findById("WP-PHASE")).thenReturn(Optional.of(WorkProcess.builder().id("WP-PHASE")
                .factoryId("F006").unit("kg").standardHourlyRate(new BigDecimal("30"))
                .standardYieldMax(new BigDecimal("1.0000")).build()));
        when(reportRepo.findYieldReportsByTask("F006", 60L)).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(601L);
            return r;
        });

        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setReportKind("INPUT");
        req.setInputQuantity(new BigDecimal("998"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(
                new MaterialBatchRef("mb-ph", new BigDecimal("500"), "kg"),
                new MaterialBatchRef("mb-ph", new BigDecimal("498"), "kg")));

        svc.submitReport("F006", 1L, 5L, req);

        verify(recorder).recordRawToProduction(eq("F006"), eq("mb-ph"), eq(1L),
                argThat(q -> q != null && q.compareTo(new BigDecimal("998")) == 0), eq("kg"), eq(5L),
                eq(LineageEdgeRecorder.reportMaterialRefKey(601L, "mb-ph")),
                argThat((Map<String, Object> m) -> Long.valueOf(601L).equals(m.get("reportId"))
                        && "APP_YIELD_REPORT".equals(m.get("origin"))));
    }

    @Test
    @DisplayName("recordMaterialInput (纯领料单): 同样写边")
    void recordMaterialInputWritesRawEdge() {
        when(taskRepo.findByFactoryIdAndId("F006", 30L)).thenReturn(Optional.of(task(30L, "WP-MAT")));
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(101L);
            return r;
        });
        MaterialInputRequest req = new MaterialInputRequest();
        req.setWorkProcessTaskId(30L);
        req.setWarehouseOutQuantity(new BigDecimal("520"));
        req.setFeedInQuantity(new BigDecimal("520"));
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(new MaterialBatchRef("mb-ph", new BigDecimal("520"), "kg")));

        svc.recordMaterialInput("F006", 1L, 5L, req);

        verify(recorder).recordRawToProduction(eq("F006"), eq("mb-ph"), eq(1L),
                argThat(q -> q != null && q.compareTo(new BigDecimal("520")) == 0), eq("kg"), eq(5L),
                eq(LineageEdgeRecorder.reportMaterialRefKey(101L, "mb-ph")),
                argThat((Map<String, Object> m) -> "APP_MATERIAL_INPUT".equals(m.get("origin"))));
    }

    @Test
    @DisplayName("阴性对照: 没有领料引用的报工不写边")
    void reportWithoutRefsWritesNothing() {
        when(taskRepo.findByFactoryIdAndId("F006", 60L)).thenReturn(Optional.of(task(60L, "WP-PHASE")));
        when(processRepo.findById("WP-PHASE")).thenReturn(Optional.empty());
        when(reportRepo.findYieldReportsByTask("F006", 60L)).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(602L);
            return r;
        });
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(60L);
        req.setInputQuantity(new BigDecimal("10"));
        req.setInputUnit("kg");
        req.setOutputQuantity(new BigDecimal("8"));
        req.setOutputUnit("kg");

        svc.submitReport("F006", 1L, 5L, req);

        verify(reportRepo).save(any(ProductionReport.class));   // 必发生: 报工确实落库了
        verify(recorder, never()).recordRawToProduction(any(), any(), any(), any(), any(), any(), any(), any());
    }

    private static WorkProcessTask task(long id, String wpId) {
        WorkProcessTask t = new WorkProcessTask();
        t.setId(id);
        t.setFactoryId("F006");
        t.setProductionBatchId(1L);
        t.setProcessOrder(1);
        t.setWorkProcessId(wpId);
        t.setProductTypeId("PT-PH");
        t.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        return t;
    }
}
