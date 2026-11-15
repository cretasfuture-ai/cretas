package com.cretas.aims.service.impl;

import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.material.MaterialBatchRelease;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.MaterialBatchAdjustmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.foodsafety.ProcessTimeLimitEventRepository;
import com.cretas.aims.repository.quality.QcReleaseRecordRepository;
import com.cretas.aims.service.alerts.InventoryLowStockEventPublisher;
import com.cretas.aims.service.FuturePlanMatchingService;
import com.cretas.aims.service.discipline.MaterialBatchReleaseGuard;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * #1723 复审 低1: 委外厂手里的料 (source_doc_type = OUTSOURCE_ISSUE 的委外仓批次) 在仓库页 / AI 批次工具
 * ({@code BatchUpdateTool} 走的也是这两个服务方法) 上只能扣留 / 放行, 不能改成鲜品 / 冻品等, 也不能改数量。
 * 走真实入口 {@link MaterialBatchServiceImpl#updateBatchStatus} / {@code adjustBatchQuantity} /
 * {@code applyBatchQuantityDelta}, 不调静态函数 (形态 B)。阳性对照: 普通批次同样的调用不被这道闸拦。
 */
@DisplayName("#1723 低1: 委外仓批次的仓库页 / AI 改状态改数量闸")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaterialBatchServiceImplOutsourceBatchGuardTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private MaterialBatchAdjustmentRepository materialBatchAdjustmentRepository;
    @Mock private RawMaterialTypeRepository materialTypeRepository;
    @Mock private MaterialBatchMapper materialBatchMapper;
    @Mock private MaterialConsumptionRepository materialConsumptionRepository;
    @Mock private ProductionPlanBatchUsageRepository productionPlanBatchUsageRepository;
    @Mock private ExcelUtil excelUtil;
    @Mock private FuturePlanMatchingService futurePlanMatchingService;
    @Mock private QcReleaseRecordRepository qcReleaseRecordRepository;
    @Mock private ProcessTimeLimitEventRepository processTimeLimitEventRepository;
    @Mock private ApplicationEventPublisher applicationEventPublisher;
    @Mock private InventoryLowStockEventPublisher inventoryLowStockEventPublisher;

    private MaterialBatchServiceImpl service;
    private static final String FACTORY = "F006";

    @BeforeEach
    void setUp() {
        service = new MaterialBatchServiceImpl(materialBatchRepository, materialBatchAdjustmentRepository,
                materialTypeRepository, materialBatchMapper, materialConsumptionRepository,
                productionPlanBatchUsageRepository, excelUtil, futurePlanMatchingService);
        when(materialBatchRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(materialBatchAdjustmentRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // 放行 / 不合格判定 / 事件发布这些可选协作者给 mock (查不到记录 = 没判过), 让阳性对照走完真实路径
        ReflectionTestUtils.setField(service, "qcReleaseRecordRepository", qcReleaseRecordRepository);
        ReflectionTestUtils.setField(service, "processTimeLimitEventRepository", processTimeLimitEventRepository);
        ReflectionTestUtils.setField(service, "applicationEventPublisher", applicationEventPublisher);
        ReflectionTestUtils.setField(service, "inventoryLowStockEventPublisher", inventoryLowStockEventPublisher);
    }

    @ParameterizedTest(name = "委外仓批次改成 {0} → 409, 状态不变")
    @EnumSource(value = MaterialBatchStatus.class, names = {"FRESH", "FROZEN", "DEPLETED", "USED_UP", "EXPIRED", "SCRAPPED", "DEFECTIVE"})
    void outsourceBatchCannotBeSetToOtherStatus(MaterialBatchStatus target) {
        MaterialBatch os = batch("OS-1", "OUTSOURCE_ISSUE");
        assertThatThrownBy(() -> service.updateBatchStatus(FACTORY, "OS-1", target,
                new MaterialBatchReleaseGuard.ManualChange(MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "改状态", 1L, "warehouse_manager")))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409))
                .hasMessageContaining("委外厂手里的料只能扣留或放行");
        assertThat(os.getStatus()).isEqualTo(MaterialBatchStatus.RESERVED);
        verify(materialBatchRepository, never()).save(any());
    }

    @Test
    @DisplayName("阳性对照: 委外仓批次扣留 (质检中) 不被这道闸拦, 真的落库")
    void outsourceBatchCanStillBeHeld() {
        MaterialBatch os = batch("OS-2", "OUTSOURCE_ISSUE");
        service.updateBatchStatus(FACTORY, "OS-2", MaterialBatchStatus.INSPECTING,
                new MaterialBatchReleaseGuard.ManualChange(MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "扣留", 1L, "warehouse_manager"));
        assertThat(os.getStatus()).isEqualTo(MaterialBatchStatus.INSPECTING);
        verify(materialBatchRepository, atLeastOnce()).save(any());
    }

    @Test
    @DisplayName("阳性对照: 普通批次改冻品不被这道闸拦, 真的落库")
    void ordinaryBatchCanBecomeFrozen() {
        MaterialBatch b = batch("MB-1", null);
        b.setStatus(MaterialBatchStatus.AVAILABLE);
        service.updateBatchStatus(FACTORY, "MB-1", MaterialBatchStatus.FROZEN,
                new MaterialBatchReleaseGuard.ManualChange(MaterialBatchRelease.Channel.WAREHOUSE_STATUS_CHANGE, "转冻品", 1L, "warehouse_manager"));
        assertThat(b.getStatus()).isEqualTo(MaterialBatchStatus.FROZEN);
    }

    @Test
    @DisplayName("委外仓批次直接改数量 (两个入口) → 409, 数量不变")
    void outsourceBatchQuantityCannotBeEdited() {
        MaterialBatch os = batch("OS-3", "OUTSOURCE_ISSUE");
        assertThatThrownBy(() -> service.adjustBatchQuantity(FACTORY, "OS-3", new BigDecimal("3"), "盘点", 1L))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThatThrownBy(() -> service.applyBatchQuantityDelta(FACTORY, "OS-3", new BigDecimal("-2"), "AI 调整"))
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> assertThat(((BusinessException) e).getCode()).isEqualTo(409));
        assertThat(os.getReceiptQuantity()).isEqualByComparingTo("10");
        verify(materialBatchRepository, never()).save(any());
        verify(materialBatchAdjustmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("阳性对照: 普通批次改数量照常落库")
    void ordinaryBatchQuantityStillEditable() {
        MaterialBatch b = batch("MB-2", null);
        service.adjustBatchQuantity(FACTORY, "MB-2", new BigDecimal("3"), "盘点", 1L);
        assertThat(b.getReceiptQuantity()).isEqualByComparingTo("3");
        verify(materialBatchRepository, atLeastOnce()).save(any());
    }

    private MaterialBatch batch(String id, String sourceDocType) {
        MaterialBatch b = new MaterialBatch();
        b.setId(id);
        b.setFactoryId(FACTORY);
        b.setBatchNumber(id + "-NO");
        b.setMaterialTypeId("MT-RAW");
        b.setReceiptQuantity(new BigDecimal("10"));
        b.setUsedQuantity(BigDecimal.ZERO);
        b.setReservedQuantity(BigDecimal.ZERO);
        b.setQuantityUnit("kg");
        b.setStatus(MaterialBatchStatus.RESERVED);
        b.setReceiptDate(LocalDate.now());
        b.setSourceDocType(sourceDocType);
        b.setCreatedBy(1L);
        when(materialBatchRepository.findById(id)).thenReturn(Optional.of(b));
        return b;
    }
}
