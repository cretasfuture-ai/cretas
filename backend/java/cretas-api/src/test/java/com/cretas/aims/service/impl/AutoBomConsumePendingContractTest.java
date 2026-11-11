package com.cretas.aims.service.impl;

import com.cretas.aims.dto.orchestration.MaterialAllocation;
import com.cretas.aims.dto.orchestration.MaterialCheckResult;
import com.cretas.aims.dto.orchestration.MaterialRequirement;
import com.cretas.aims.dto.orchestration.MaterialShortfall;
import com.cretas.aims.entity.MaterialConsumption;
import com.cretas.aims.entity.ProductionAlert;
import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductionAlertRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.MaterialBatchService;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.orchestration.BomExpansionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 排查 2026-11-10 A9: BOM 自动扣料 ({@link BatchConsumptionServiceImpl#autoConsumeForBatch})。
 * <ul>
 *   <li>没有实际产量时按计划量扣 —— 拿计划量冒充实际。改成: 不扣, 挂待办 (ACTIVE 生产告警)。</li>
 *   <li>某批扣失败只写日志然后继续 —— 库存扣一半、成本少算, 界面无信号。改成: 异常抛出 (REQUIRES_NEW 整段回滚),
 *       调用方挂待办 (调用方那一半见 {@code SupplyChainOrchestratorAutoConsumePendingContractTest})。</li>
 *   <li>缺料只写日志 —— 改成同时挂待办。</li>
 * </ul>
 */
@DisplayName("排查 A9: 自动扣料没实际产量不扣、扣失败不留半截、没扣成都挂待办")
class AutoBomConsumePendingContractTest {

    private static final String F = "F006";
    private static final Long PB = 777L;

    private final BomExpansionService bom = mock(BomExpansionService.class);
    private final MaterialBatchService materialBatchService = mock(MaterialBatchService.class);
    private final MaterialConsumptionRepository consumptions = mock(MaterialConsumptionRepository.class);
    private final ProductionBatchRepository productionBatches = mock(ProductionBatchRepository.class);
    private final ProductionAlertRepository alerts = mock(ProductionAlertRepository.class);
    private final List<MaterialConsumption> persisted = new ArrayList<>();

    private BatchConsumptionServiceImpl service;
    private ProductionBatch batch;
    private MaterialCheckResult check;

    @BeforeEach
    void setUp() {
        service = new BatchConsumptionServiceImpl(bom, materialBatchService, mock(MaterialBatchRepository.class),
                consumptions, productionBatches, mock(RawMaterialTypeRepository.class), mock(WarehouseResolver.class));
        ReflectionTestUtils.setField(service, "productionAlertRepository", alerts);
        when(alerts.save(any(ProductionAlert.class))).thenAnswer(i -> i.getArgument(0));

        batch = new ProductionBatch();
        batch.setId(PB);
        batch.setFactoryId(F);
        batch.setBatchNumber("QA-PB-777");
        batch.setProductTypeId("PT-1");
        batch.setProductionPlanId("PP-1");
        batch.setPlannedQuantity(new BigDecimal("100"));
        batch.setSupervisorId(7L);

        when(consumptions.findByProductionBatchIdAndSourceType(PB, "AUTO_BOM")).thenReturn(List.of());
        when(consumptions.save(any(MaterialConsumption.class))).thenAnswer(i -> {
            persisted.add(i.getArgument(0));
            return i.getArgument(0);
        });
        when(consumptions.findByProductionBatchIdAndFactoryId(PB, F)).thenAnswer(i -> new ArrayList<>(persisted));

        MaterialRequirement req = new MaterialRequirement();
        req.setMaterialTypeId("MAT-1");
        req.setMaterialTypeName("猪肉");
        req.setRequiredQuantity(new BigDecimal("10"));
        when(bom.expandBOM(eq(F), eq("PT-1"), any(BigDecimal.class))).thenReturn(List.of(req));

        check = new MaterialCheckResult();
        check.setAllocations(List.of(alloc("MB-A", "4"), alloc("MB-B", "6")));
        check.setShortfalls(List.of());
        when(bom.checkMaterialAvailability(eq(F), any())).thenReturn(check);
    }

    private static MaterialAllocation alloc(String batchId, String qty) {
        MaterialAllocation a = new MaterialAllocation();
        a.setMaterialTypeId("MAT-1");
        a.setMaterialBatchId(batchId);
        a.setAllocatedQuantity(new BigDecimal(qty));
        return a;
    }

    private ProductionAlert onlyAlert() {
        ArgumentCaptor<ProductionAlert> cap = ArgumentCaptor.forClass(ProductionAlert.class);
        verify(alerts).save(cap.capture());
        ProductionAlert a = cap.getValue();
        assertThat(a.getAlertType()).isEqualTo(BatchConsumptionServiceImpl.ALERT_AUTO_CONSUME);
        assertThat(a.getStatus()).isEqualTo("ACTIVE");
        assertThat(a.getFactoryId()).isEqualTo(F);
        assertThat(a.getBatchId()).isEqualTo(PB);
        return a;
    }

    @Test
    @DisplayName("没有实际产量 (只有计划量 100): 不按计划量扣, 一克不扣、不记消耗, 挂待办")
    void noActualQuantityDoesNotDeductAndRaisesPending() {
        service.autoConsumeForBatch(batch);

        verify(bom, never()).expandBOM(anyString(), anyString(), any());
        verify(materialBatchService, never()).useBatchQuantity(anyString(), anyString(), any());
        verify(consumptions, never()).save(any());
        assertThat(onlyAlert().getDescription()).contains("QA-PB-777").contains("实际产量");
    }

    @Test
    @DisplayName("阳性对照: 有实际产量 10 → 按实际产量展开 BOM (不是计划量 100), 两批都扣, 不挂待办")
    void actualQuantityDrivesTheDeduction() {
        batch.setActualQuantity(new BigDecimal("10"));
        service.autoConsumeForBatch(batch);

        verify(bom).expandBOM(F, "PT-1", new BigDecimal("10"));
        verify(materialBatchService).useBatchQuantity(F, "MB-A", new BigDecimal("4"));
        verify(materialBatchService).useBatchQuantity(F, "MB-B", new BigDecimal("6"));
        assertThat(persisted).hasSize(2);
        verify(alerts, never()).save(any());
    }

    @Test
    @DisplayName("第二批扣失败: 异常抛出 (不吞), 由 REQUIRES_NEW 整段回滚 —— 第二批之后不再写消耗、不更新批次成本")
    void allocationFailureAbortsInsteadOfContinuing() {
        batch.setActualQuantity(new BigDecimal("10"));
        doThrow(new BusinessException(409, "批次可用数量不足"))
                .when(materialBatchService).useBatchQuantity(F, "MB-B", new BigDecimal("6"));

        assertThatThrownBy(() -> service.autoConsumeForBatch(batch))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不足");
        // 修复前: catch 后只写日志继续, 走到第 5 步把「只扣了一半」的成本写进批次
        verify(productionBatches, never()).save(any());
        assertThat(persisted).hasSize(1);   // 第一批那条在同一事务里, 抛出后随事务回滚 (由 Spring 负责)
    }

    @Test
    @DisplayName("缺料: 能扣的照扣, 缺的那部分挂待办写明缺什么, ⛔ 不只写日志")
    void shortfallRaisesPending() {
        batch.setActualQuantity(new BigDecimal("10"));
        MaterialShortfall s = new MaterialShortfall();
        s.setMaterialTypeId("MAT-2");
        s.setMaterialTypeName("淀粉");
        s.setRequiredQuantity(new BigDecimal("3"));
        s.setAvailableQuantity(BigDecimal.ONE);
        s.setShortfallQuantity(new BigDecimal("2"));
        check.setShortfalls(List.of(s));

        service.autoConsumeForBatch(batch);

        assertThat(persisted).hasSize(2);
        assertThat(onlyAlert().getDescription()).contains("淀粉").contains("2");
    }

    @Test
    @DisplayName("告警仓储没注入: 要挂待办时直接报错, ⛔ 不当没事")
    void missingAlertRepositoryFailsLoud() {
        ReflectionTestUtils.setField(service, "productionAlertRepository", null);
        assertThatThrownBy(() -> service.autoConsumeForBatch(batch)).isInstanceOf(IllegalStateException.class);
    }
}
