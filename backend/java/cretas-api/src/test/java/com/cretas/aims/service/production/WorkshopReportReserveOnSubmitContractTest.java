package com.cretas.aims.service.production;

import com.cretas.aims.dto.yield.MaterialBatchRef;
import com.cretas.aims.dto.yield.YieldReportRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionReport;
import com.cretas.aims.entity.SemiFinishedInventory;
import com.cretas.aims.entity.WorkProcess;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.workprocess.WorkProcessTask;
import com.cretas.aims.repository.lineage.BatchLineageEdgeRepository;
import com.cretas.aims.repository.FactorySettingsRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.recipe.ProcessMaterialRecipeRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductWorkProcessAssigneeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.ProductionReportRepository;
import com.cretas.aims.repository.SemiFinishedInventoryRepository;
import com.cretas.aims.repository.WorkProcessRepository;
import com.cretas.aims.repository.ProcessSheetRowRepository;
import com.cretas.aims.repository.workprocess.WorkProcessTaskRepository;
import com.cretas.aims.service.ProcessingService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.wip.WipInventoryService;
import com.cretas.aims.service.yield.impl.YieldCalculationServiceImpl;
import com.cretas.aims.service.yield.impl.YieldReportServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 🔒 钉住<b>占用这一半真的接上了</b> —— 跑在报工提交的真实入口
 * {@link YieldReportServiceImpl#submitReport} 上。
 *
 * <h3>为什么单独一条闸</h3>
 *
 * <p>{@link WorkshopReportReservationService} 自己的单测只证明「算得对」,
 * 证明不了「生产上谁调它」。本仓反复栽在这个形状上: 机制在、测试绿, 而产品那条路根本没调它。
 * 占用侧尤其危险 —— 它是 {@code @Autowired(required = false)} 字段, 接不上时
 * <b>既不报错也不留痕</b>, 表现就是「这个功能好像没生效」。
 *
 * <p>⚠️ 占用点<b>有两处</b> ({@code submitReport} / {@code recordMaterialInput}, 都在
 * {@code YieldReportServiceImpl} 里建带 {@code materialBatchRefs} 的报工)。本闸钉住
 * 前者 —— 那是 App 报工的主路径, 也是 2026-08-18 prod 上唯一有数据的那条。
 */
@DisplayName("报工提交即占用生产仓 契约")
class WorkshopReportReserveOnSubmitContractTest {

    private static final String FACTORY = "F006";
    private static final Long PRODUCTION_BATCH_ID = 10761L;
    private static final Long WORKER_ID = 1310L;
    private static final Long TASK_ID = 1786L;
    private static final String MATERIAL_BATCH_ID = "0ed1830f-b5e0-4305-99f2-41f7e7a7d61a";
    private static final String WORKSHOP_WAREHOUSE_ID = "7f3e6f4a-2d1a-4f8e-b2c4-6a9d0e1f3b5c";
    private static final BigDecimal RECEIPT = new BigDecimal("20");
    private static final BigDecimal REF_QTY = new BigDecimal("20");

    private ProductionReportRepository reportRepo;
    private WorkProcessTaskRepository taskRepo;
    private WorkProcessRepository processRepo;
    private MaterialBatchRepository materialBatchRepository;
    private YieldReportServiceImpl svc;
    private EntityManager entityManager;
    private MaterialBatch batch;

    @BeforeEach
    void setUp() {
        reportRepo = mock(ProductionReportRepository.class);
        taskRepo = mock(WorkProcessTaskRepository.class);
        processRepo = mock(WorkProcessRepository.class);
        materialBatchRepository = mock(MaterialBatchRepository.class);
        entityManager = mock(EntityManager.class);
        SemiFinishedInventoryRepository wipRepo = mock(SemiFinishedInventoryRepository.class);
        ProcessMaterialRecipeRepository recipeRepo = mock(ProcessMaterialRecipeRepository.class);
        ProductWorkProcessAssigneeRepository pwpAssigneeRepository =
                mock(ProductWorkProcessAssigneeRepository.class);

        when(wipRepo.findByFactoryIdAndIntermediateBatchNoAndDeletedAtIsNull(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(wipRepo.findByFactoryIdAndBatchIdAndDeletedAtIsNull(anyString(), any())).thenReturn(List.of());
        when(wipRepo.save(any(SemiFinishedInventory.class))).thenAnswer(i -> i.getArgument(0));
        when(recipeRepo.findActiveByFactoryIdAndWorkProcessId(anyString(), anyString())).thenReturn(List.of());
        when(pwpAssigneeRepository.findByProductWorkProcessId(any())).thenReturn(List.of());

        svc = new YieldReportServiceImpl(
                reportRepo, taskRepo, processRepo,
                new YieldCalculationServiceImpl(),
                mock(ProcessingService.class),
                mock(FactorySettingsRepository.class),
                materialBatchRepository,
                mock(ProductTypeRepository.class),
                mock(ProductionBatchRepository.class),
                mock(ProductionPlanRepository.class),
                wipRepo, mock(BatchLineageEdgeRepository.class),
                new ObjectMapper(),
                recipeRepo, mock(WipInventoryService.class), pwpAssigneeRepository,
                mock(com.cretas.aims.repository.ProductWorkProcessRepository.class),
                new com.cretas.aims.service.yield.CostReconcileService(),
                mock(ProcessSheetRowRepository.class));

        batch = workshopBatch();
        // ⚠️ 与生产同一个 bean: 预留服务和被测 service 共用 materialBatchRepository。
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate(MATERIAL_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(batch));
        when(materialBatchRepository.findByIdAndFactoryId(MATERIAL_BATCH_ID, FACTORY))
                .thenReturn(Optional.of(batch));
        when(materialBatchRepository.findById(MATERIAL_BATCH_ID)).thenReturn(Optional.of(batch));
        when(materialBatchRepository.save(any(MaterialBatch.class))).thenAnswer(i -> i.getArgument(0));

        WorkProcessTask task = new WorkProcessTask();
        task.setId(TASK_ID);
        task.setFactoryId(FACTORY);
        task.setProductionBatchId(PRODUCTION_BATCH_ID);
        task.setProcessOrder(1);
        task.setWorkProcessId("WP-CHAIGU");
        task.setStatus(WorkProcessTask.Status.IN_PROGRESS);
        task.setProductTypeId("PT-ZS");
        when(taskRepo.findByFactoryIdAndId(FACTORY, TASK_ID)).thenReturn(Optional.of(task));
        when(processRepo.findById("WP-CHAIGU")).thenReturn(Optional.of(new WorkProcess()));
        when(reportRepo.findYieldReportsByBatch(anyString(), any())).thenReturn(List.of());
        when(reportRepo.findYieldReportsByTask(FACTORY, TASK_ID)).thenReturn(List.of());
        when(reportRepo.save(any(ProductionReport.class))).thenAnswer(i -> {
            ProductionReport r = i.getArgument(0);
            r.setId(23836L);
            return r;
        });
        when(taskRepo.save(any(WorkProcessTask.class))).thenAnswer(i -> i.getArgument(0));
    }

    private void wireReservation(boolean wired) {
        WarehouseResolver warehouseResolver = mock(WarehouseResolver.class);
        when(warehouseResolver.resolveWorkshopId(FACTORY)).thenReturn(WORKSHOP_WAREHOUSE_ID);
        ReflectionTestUtils.setField(svc, "workshopReservationService", wired
                ? new WorkshopReportReservationService(
                        materialBatchRepository,
                        mock(ProductionBatchRepository.class),
                        mock(ProductionReportRepository.class),
                        warehouseResolver,
                        entityManager)
                : null);
    }

    private YieldReportRequest inputReportOf(BigDecimal quantity) {
        MaterialBatchRef ref = new MaterialBatchRef();
        ref.setMaterialBatchId(MATERIAL_BATCH_ID);
        ref.setQuantity(quantity);
        ref.setUnit("kg");
        YieldReportRequest req = new YieldReportRequest();
        req.setWorkProcessTaskId(TASK_ID);
        req.setReportKind("INPUT");
        req.setInputQuantity(quantity);
        req.setInputUnit("kg");
        req.setMaterialBatchRefs(List.of(ref));
        return req;
    }

    @Test
    @DisplayName("🔑 App 报工提交后, 引用的生产仓批次立刻被占上 —— 别的计划从此领不走它")
    void submitReservesTheReferencedWorkshopBatch() {
        wireReservation(true);

        svc.submitReport(FACTORY, PRODUCTION_BATCH_ID, WORKER_ID, inputReportOf(REF_QTY));

        assertThat(batch.getReservedQuantity()).isEqualByComparingTo(REF_QTY);
        assertThat(batch.getCurrentQuantity())
                .as("可用量 = 在手 − 已提交报工引用量 ⇒ 归 0").isEqualByComparingTo(BigDecimal.ZERO);
        // 阳性对照: 占用不是扣减 —— 货还在货架上。
        assertThat(batch.getUsedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getPhysicalQuantity()).isEqualByComparingTo(RECEIPT);
    }

    @Test
    @DisplayName("缺少必需占用接线时拒绝报工，报告和库存均无写入")
    void withoutTheWiringSubmitIsRejectedBeforeReportSave() {
        wireReservation(false);   // ← 变异: 唯一的占用出口被摘掉

        assertThatThrownBy(() -> svc.submitReport(FACTORY, PRODUCTION_BATCH_ID, WORKER_ID, inputReportOf(REF_QTY)))
                .isInstanceOf(com.cretas.aims.exception.BusinessException.class)
                .hasMessageContaining("原料占用服务暂不可用");
        verify(reportRepo, never()).save(any());
        assertThat(batch.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(batch.getCurrentQuantity()).isEqualByComparingTo(RECEIPT);
    }

    @Test
    @DisplayName("阴性对照: 没有领料引用的报工不占任何料 (⛔ 不许凭空占)")
    void submitWithoutMaterialRefsReservesNothing() {
        wireReservation(true);
        YieldReportRequest req = inputReportOf(REF_QTY);
        req.setMaterialBatchRefs(null);

        svc.submitReport(FACTORY, PRODUCTION_BATCH_ID, WORKER_ID, req);

        assertThat(batch.getReservedQuantity()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private static MaterialBatch workshopBatch() {
        MaterialBatch b = new MaterialBatch();
        b.setId(MATERIAL_BATCH_ID);
        b.setFactoryId(FACTORY);
        b.setBatchNumber("MT-20260817-8486-WKS-d1848432");
        b.setMaterialTypeId("RM-1");
        b.setReceiptQuantity(RECEIPT);
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setWarehouseId(WORKSHOP_WAREHOUSE_ID);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        return b;
    }
}
