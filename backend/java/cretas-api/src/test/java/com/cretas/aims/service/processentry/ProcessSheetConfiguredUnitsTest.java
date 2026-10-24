package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.ProductWorkProcess;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProcessSheetRowChangeLogRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.inventory.FinishedGoodsBatchRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.inventory.FinishedGoodsFeedService;
import com.cretas.aims.service.processentry.ClerkProcessEntryService;
import com.cretas.aims.service.processentry.impl.ProcessSheetServiceImpl;
import com.cretas.aims.service.wip.WipInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class ProcessSheetConfiguredUnitsTest {

    @Mock private ClerkProcessEntryService clerkService;
    @Mock private ProcessSheetRowRepository rowRepo;
    @Mock private MaterialBatchRepository materialBatchRepo;
    @Mock private ProductionBatchRepository productionBatchRepo;
    @Mock private MaterialConsumptionRepository consumptionRepo;
    @Mock private ProductionReportRepository reportRepo;
    @Mock private ProductionPlanRepository productionPlanRepo;
    @Mock private ProcessSheetRowChangeLogRepository changeLogRepo;
    @Mock private SemiFinishedInventoryRepository wipRepo;
    @Mock private WorkProcessTaskRepository taskRepo;
    @Mock private WorkProcessRepository processRepo;
    @Mock private ProductWorkProcessRepository productWorkProcessRepo;
    @Mock private ProductTypeRepository productTypeRepo;
    @Mock private FinishedGoodsBatchRepository finishedGoodsBatchRepo;
    @Mock private WipInventoryService wipInventoryService;
    @Mock private FinishedGoodsFeedService finishedGoodsFeedService;

    private ProcessSheetServiceImpl service;

    @BeforeEach
    void setUpService() {
        service = new ProcessSheetServiceImpl(
                clerkService, rowRepo, materialBatchRepo, productionBatchRepo,
                consumptionRepo, reportRepo, productionPlanRepo, changeLogRepo,
                new ObjectMapper(), wipRepo, taskRepo, processRepo,
                productWorkProcessRepo, productTypeRepo, finishedGoodsBatchRepo,
                wipInventoryService, finishedGoodsFeedService);
    }

    @Test
    void normalizesRequestUnitsFromProductProcessConfiguration() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("each");
        request.setOutputUnit("bag");

        configuredUnitsMethod().invoke(null, request, productProcess("each"), workProcess("each", "bag"));

        assertEquals("each", request.getInputUnit());
        assertEquals("bag", request.getOutputUnit());
        assertEquals("bag", request.getUnit());
    }

    @Test
    void rejectsClientUnitThatDiffersFromConfiguredProcess() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("box");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> configuredUnitsMethod().invoke(null, request, productProcess("each"), workProcess("each", "bag")));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_UNIT_MISMATCH", error.getErrorCode());
    }

    @Test
    void acceptsChineseDisplayAliasForCanonicalConfiguredUnit() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("盒");
        request.setUnit("盒");

        configuredUnitsMethod().invoke(null, request, productProcess("kg"), workProcess("kg", "box"));

        assertEquals("kg", request.getInputUnit());
        assertEquals("box", request.getOutputUnit());
        assertEquals("box", request.getUnit());
    }

    @Test
    void doesNotTreatCaseAsAliasForBox() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("kg");
        request.setOutputUnit("箱");

        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> configuredUnitsMethod().invoke(null, request, productProcess("kg"), workProcess("kg", "box")));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_UNIT_MISMATCH", error.getErrorCode());
    }

    @Test
    void rejectsExternalSemiFinishedFeedForNonKgConfiguredInput() throws Exception {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setInputUnit("bag");
        ProcessSheetRowRequest.UpstreamRef source = new ProcessSheetRowRequest.UpstreamRef();
        source.setSemiFinished(true);
        request.setUpstreamSources(List.of(source));

        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "assertExternalFeedUnitSupported", ProcessSheetRowRequest.class);
        method.setAccessible(true);
        InvocationTargetException thrown = assertThrows(InvocationTargetException.class,
                () -> method.invoke(null, request));

        BusinessException error = (BusinessException) thrown.getCause();
        assertEquals("PROCESS_SHEET_EXTERNAL_FEED_UNIT_UNSUPPORTED", error.getErrorCode());
    }

    @Test
    void savesKgExternalSemiFinishedFeedThroughTheActualSaveRowPath() {
        ProcessSheetRowRequest request = new ProcessSheetRowRequest();
        request.setClientRowId("ROW-EXTERNAL-KG");
        request.setProcessCode("pack");
        request.setProcessOrder(1);
        request.setProductTypeId("PT-FINISHED");
        request.setUnit("kg");
        request.setOutputQuantity(BigDecimal.ZERO);
        ProcessSheetRowRequest.UpstreamRef source = new ProcessSheetRowRequest.UpstreamRef();
        source.setSourceBatchNumber("SFI-001");
        source.setFeedQuantityKg(BigDecimal.ONE);
        source.setSkuId("PT-SEMI");
        source.setSemiFinished(true);
        request.setUpstreamSources(List.of(source));

        com.cretas.aims.entity.ProductionPlan plan = new com.cretas.aims.entity.ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        when(productionPlanRepo.findByIdAndFactoryId("PLAN-1", "F006"))
                .thenReturn(Optional.of(plan));
        when(productWorkProcessRepo.findByFactoryIdAndProductTypeIdAndProcessOrder(
                "F006", "PT-FINISHED", 1)).thenReturn(Optional.empty());
        SemiFinishedInventory inventory = new SemiFinishedInventory();
        inventory.setFactoryId("F006");
        inventory.setIntermediateBatchNo("SFI-001");
        inventory.setProductTypeId("PT-SEMI");
        inventory.setStatus(SemiFinishedInventory.Status.AVAILABLE);
        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull("F006", "SFI-001"))
                .thenReturn(Optional.of(inventory));
        when(rowRepo.findByFactoryIdAndPlanIdAndProcessCodeAndClientRowId(
                "F006", "PLAN-1", "pack", "ROW-EXTERNAL-KG"))
                .thenReturn(Optional.empty());

        var result = service.saveRow("F006", "PLAN-1", request, 7L);

        assertFalse(result.isMaterialized());
    }

    @Test
    void treatsCanonicalPackagingAliasesAsTheSameNativeStockUnit() throws Exception {
        // 2026-08-14: 该方法由 static 改成实例方法并多收一个 factoryId ——
        // 判等从私有折叠表换成权威表 (configuredUnitsEquivalent)。这三条断的是
        // 「同一个单位的中英两种写法」, 换表之后必须照旧放行, 否则就是把 2026-07-31
        // 那条误拦引回来了。
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "convertReportingQuantityToStorage",
                String.class, BigDecimal.class, String.class, String.class, String.class);
        method.setAccessible(true);
        ProcessSheetServiceImpl impl = org.mockito.Mockito.mock(
                ProcessSheetServiceImpl.class, org.mockito.Mockito.CALLS_REAL_METHODS);

        assertEquals(new BigDecimal("10"), method.invoke(
                impl, "F006", new BigDecimal("10"), "box", "盒", "成品盒"));
        assertEquals(new BigDecimal("10"), method.invoke(
                impl, "F006", new BigDecimal("10"), "slice", "片", "封膜"));
        assertEquals(new BigDecimal("1.25"), method.invoke(
                impl, "F006", new BigDecimal("1.25"), "case", "箱", "外箱"));
    }

    private static Method configuredUnitsMethod() throws Exception {
        Method method = ProcessSheetServiceImpl.class.getDeclaredMethod(
                "normalizeConfiguredUnits", ProcessSheetRowRequest.class, ProductWorkProcess.class, WorkProcess.class);
        method.setAccessible(true);
        return method;
    }

    private static ProductWorkProcess productProcess(String unitOverride) {
        ProductWorkProcess process = new ProductWorkProcess();
        process.setUnitOverride(unitOverride);
        return process;
    }

    private static WorkProcess workProcess(String inputUnit, String outputUnit) {
        WorkProcess process = new WorkProcess();
        process.setUnit(inputUnit);
        process.setOutputUnit(outputUnit);
        process.setProcessName("test process");
        return process;
    }
}
