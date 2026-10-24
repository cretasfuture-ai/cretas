package com.cretas.aims.service.processentry;

import com.cretas.aims.dto.processentry.ProcessSheetRowRequest;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.enums.MaterialBatchStatus;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.ProductionInputAllocationRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.processentry.impl.ProductionStockAllocationServiceImpl;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.unit.UnitNormalizationResult;
import com.cretas.aims.util.InventoryExpiry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 原料投料在动作当下比较到期日 —— 不依赖 02:00 定时任务是否已把批次置 EXPIRED。
 * 口径 (InventoryExpiry): 到期日当天仍可投, 次日起拒; 无到期日维持现状。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("报工投料 — 过期原料不能投")
class ProductionStockExpiryEnforcementTest {

    @Mock private MaterialBatchRepository materialBatchRepository;
    @Mock private ProductionInputAllocationRepository allocationRepository;
    @Mock private ProductionPlanRepository productionPlanRepository;
    @Mock private WarehouseResolver warehouseResolver;
    @Mock private UnitContractService unitContractService;
    @Mock private RawMaterialTypeRepository rawMaterialTypeRepository;

    private ProductionStockAllocationService service;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        today = InventoryExpiry.today();
        service = new ProductionStockAllocationServiceImpl(
                materialBatchRepository, allocationRepository, productionPlanRepository,
                warehouseResolver, unitContractService, rawMaterialTypeRepository);
        lenient().when(unitContractService.normalize(anyString(), anyString())).thenAnswer(call -> {
            String raw = call.getArgument(1);
            String code = "kg".equalsIgnoreCase(raw.trim()) ? "kg" : null;
            return new UnitNormalizationResult(raw, code, null);
        });
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setFactoryId("F006");
        plan.setMaterialSupplyMode(MaterialSupplyMode.FACTORY_SUPPLIED);
        when(productionPlanRepository.findByIdAndFactoryId("PLAN-1", "F006")).thenReturn(Optional.of(plan));
        when(warehouseResolver.resolveWorkshopId("F006")).thenReturn("WKS-1");
        when(allocationRepository.sumPendingQuantityByMaterialBatchId(eq("F006"), any())).thenReturn(BigDecimal.ZERO);
    }

    @Test
    @DisplayName("指定批次投料: 昨天到期 → 409 PRODUCTION_INPUT_BATCH_EXPIRED, 批号+到期日")
    void explicitBatchExpiredYesterdayIsRejected() {
        MaterialBatch expired = batch("B-OLD", today.minusDays(1));
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B-OLD", "F006")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> service.planExplicit("F006", "PLAN-1", List.of(rawInput("B-OLD", "2"))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.getCode()).isEqualTo(409);
                    assertThat(error.getErrorCode()).isEqualTo("PRODUCTION_INPUT_BATCH_EXPIRED");
                    assertThat(error.getMessage()).contains("BN-B-OLD", today.minusDays(1).toString());
                });
    }

    @Test
    @DisplayName("指定批次投料: 今天到期 → 照常投 (到期日当天仍可用)")
    void explicitBatchExpiringTodayIsAllocated() {
        MaterialBatch todayBatch = batch("B-TODAY", today);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B-TODAY", "F006")).thenReturn(Optional.of(todayBatch));

        assertThat(service.planExplicit("F006", "PLAN-1", List.of(rawInput("B-TODAY", "2"))))
                .singleElement()
                .satisfies(a -> assertThat(a.materialBatchId()).isEqualTo("B-TODAY"));
    }

    @Test
    @DisplayName("指定批次投料: 无到期日 → 维持现状, 照常投")
    void explicitBatchWithoutExpireDateIsAllocated() {
        MaterialBatch noDate = batch("B-NODATE", null);
        when(materialBatchRepository.findByIdAndFactoryIdForUpdate("B-NODATE", "F006")).thenReturn(Optional.of(noDate));

        assertThat(service.planExplicit("F006", "PLAN-1", List.of(rawInput("B-NODATE", "2"))))
                .singleElement()
                .satisfies(a -> assertThat(a.materialBatchId()).isEqualTo("B-NODATE"));
    }

    @Test
    @DisplayName("自动分摊: FEFO 排在最前的过期批 (状态仍 AVAILABLE) 被跳过, 从未过期批扣")
    void automaticAllocationSkipsExpiredBatch() {
        MaterialBatch expired = batch("B-OLD", today.minusDays(1));
        MaterialBatch fresh = batch("B-FRESH", today.plusDays(3));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate("F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(expired, fresh));

        assertThat(service.plan("F006", "PLAN-1", List.of(total("2"))))
                .extracting(ProductionStockAllocationService.PlannedAllocation::materialBatchId)
                .containsExactly("B-FRESH");
    }

    @Test
    @DisplayName("自动分摊: 只剩过期批 → 短缺 (可用 0), 不会吃掉过期料")
    void automaticAllocationWithOnlyExpiredStockIsShortage() {
        MaterialBatch expired = batch("B-OLD", today.minusDays(1));
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouseForUpdate("F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(expired));

        assertThatThrownBy(() -> service.plan("F006", "PLAN-1", List.of(total("2"))))
                .isInstanceOfSatisfying(ProductionStockShortageException.class, error ->
                        assertThat(error.getShortage().getAvailable()).isEqualByComparingTo("0"));
    }

    @Test
    @DisplayName("可投量展示与提交同口径: 过期批不计入 available")
    void availabilityExcludesExpiredBatch() {
        when(materialBatchRepository.findAvailableBatchesFEFOByWarehouse("F006", "RAW-1", "WKS-1"))
                .thenReturn(List.of(batch("B-OLD", today.minusDays(1)), batch("B-FRESH", today)));

        assertThat(service.availability("F006", "PLAN-1", List.of(total("2"))))
                .singleElement()
                .satisfies(stock -> assertThat(stock.available()).isEqualByComparingTo("5"));
    }

    private static ProcessSheetRowRequest.RawInput rawInput(String batchId, String qty) {
        ProcessSheetRowRequest.RawInput input = new ProcessSheetRowRequest.RawInput();
        input.setMaterialBatchId(batchId);
        input.setSkuId("RAW-1");
        input.setQuantity(new BigDecimal(qty));
        return input;
    }

    private static ProcessSheetRowRequest.MaterialInputTotal total(String qty) {
        ProcessSheetRowRequest.MaterialInputTotal input = new ProcessSheetRowRequest.MaterialInputTotal();
        input.setMaterialTypeId("RAW-1");
        input.setQuantity(new BigDecimal(qty));
        input.setUnit("kg");
        return input;
    }

    private static MaterialBatch batch(String id, LocalDate expireDate) {
        MaterialBatch batch = new MaterialBatch();
        batch.setId(id);
        batch.setBatchNumber("BN-" + id);
        batch.setFactoryId("F006");
        batch.setMaterialTypeId("RAW-1");
        batch.setWarehouseId("WKS-1");
        batch.setReceiptQuantity(new BigDecimal("5"));
        batch.setUsedQuantity(BigDecimal.ZERO);
        batch.setReservedQuantity(BigDecimal.ZERO);
        batch.setQuantityUnit("kg");
        batch.setStatus(MaterialBatchStatus.AVAILABLE);
        batch.setExpireDate(expireDate);
        return batch;
    }
}
