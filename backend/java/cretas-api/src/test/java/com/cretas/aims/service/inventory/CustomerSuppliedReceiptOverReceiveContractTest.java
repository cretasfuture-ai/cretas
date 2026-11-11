package com.cretas.aims.service.inventory;

import com.cretas.aims.dto.inventory.CustomerSuppliedMaterialReceiptRequest;
import com.cretas.aims.entity.Attachment;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.SalesOrderStatus;
import com.cretas.aims.entity.enums.SalesOrderSuppliedMaterialRequirementStatus;
import com.cretas.aims.entity.MaterialBatch;
import com.cretas.aims.entity.inventory.SalesOrder;
import com.cretas.aims.entity.inventory.SalesOrderItem;
import com.cretas.aims.entity.inventory.SalesOrderSuppliedMaterialRequirement;
import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.mapper.MaterialBatchMapper;
import com.cretas.aims.repository.AttachmentRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.factory.FactoryWarehouseRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.repository.inventory.SalesOrderSuppliedMaterialRequirementRepository;
import com.cretas.aims.service.unit.UnitContractService;
import com.cretas.aims.service.factory.WarehouseInventoryGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A15 (工厂纪律排查 2026-11-10; Steve 2026-11-11 拍板做成工厂开关 inventory.customer_supplied.allow_over_receive, 默认关)。
 * 开关关 (默认): 原行为, 客供料多收 1 克都拦, 根本不看采购超收上限。
 * 开关开: 与采购同一个口径, 累计实收 ≤ 应收 × (1 + 采购超收上限), 上限取值走
 * {@link PurchaseOverReceivePolicy#effectiveLimit(String)} (工厂设置 > 全局属性 > 不限制)。
 * 驱动产品真入口 {@code receive}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerSuppliedReceiptOverReceiveContractTest {

    @Mock SalesOrderSuppliedMaterialRequirementRepository requirementRepository;
    @Mock SalesOrderItemRepository salesOrderItemRepository;
    @Mock SalesOrderRepository salesOrderRepository;
    @Mock RawMaterialTypeRepository rawMaterialTypeRepository;
    @Mock FactoryWarehouseRepository factoryWarehouseRepository;
    @Mock WarehouseInventoryGuardService warehouseInventoryGuardService;
    @Mock UnitContractService unitContractService;
    @Mock MaterialBatchRepository materialBatchRepository;
    @Mock MaterialBatchMapper materialBatchMapper;
    @Mock AttachmentRepository attachmentRepository;
    @Mock PurchaseOverReceivePolicy overReceivePolicy;
    @Mock com.cretas.aims.service.discipline.FactoryDisciplinePolicy disciplinePolicy;

    private SalesOrderSuppliedMaterialRequirementService service;
    private SalesOrderSuppliedMaterialRequirement requirement;

    @BeforeEach
    void setUp() {
        service = new SalesOrderSuppliedMaterialRequirementService(requirementRepository, salesOrderItemRepository,
                salesOrderRepository, rawMaterialTypeRepository, factoryWarehouseRepository,
                warehouseInventoryGuardService, unitContractService, materialBatchRepository, materialBatchMapper,
                attachmentRepository);
        ReflectionTestUtils.setField(service, "overReceivePolicy", overReceivePolicy);
        ReflectionTestUtils.setField(service, "disciplinePolicy", disciplinePolicy);
        // 下面「与采购同口径」的用例都在开关【开】时成立; 关的情况见末尾的 SwitchOff 用例
        when(disciplinePolicy.customerSuppliedOverReceiveAllowed("F006")).thenReturn(true);

        requirement = new SalesOrderSuppliedMaterialRequirement();
        requirement.setId("task-qa-a15");
        requirement.setFactoryId("F006");
        requirement.setCustomerId("customer-qa");
        requirement.setSalesOrderId("order-qa");
        requirement.setSalesOrderItemId(1501L);
        requirement.setMaterialTypeId("material-qa");
        requirement.setMaterialName("QA-A15-客供鸡胸");
        requirement.setExpectedQuantity(new BigDecimal("10"));
        requirement.setReceivedQuantity(new BigDecimal("4"));
        requirement.setUnit("kg");
        requirement.setTargetWarehouseId("warehouse-raw");
        requirement.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);

        SalesOrder order = new SalesOrder();
        order.setId("order-qa");
        order.setFactoryId("F006");
        order.setCustomerId("customer-qa");
        order.setStatus(SalesOrderStatus.FINANCE_APPROVED);
        SalesOrderItem item = new SalesOrderItem();
        item.setId(1501L);
        item.setSalesOrderId("order-qa");
        RawMaterialType material = new RawMaterialType();
        material.setId("material-qa");
        material.setFactoryId("F006");
        material.setUnit("kg");
        material.setCategory("原料");
        material.setIsActive(true);

        when(materialBatchRepository.findByFactoryIdAndSourceDocTypeAndSourceEventKey(any(), any(), any()))
                .thenReturn(Optional.empty());
        when(requirementRepository.findByIdAndFactoryIdForUpdate("task-qa-a15", "F006"))
                .thenReturn(Optional.of(requirement));
        when(salesOrderRepository.findByIdAndFactoryIdForUpdate("order-qa", "F006")).thenReturn(Optional.of(order));
        when(salesOrderItemRepository.findById(1501L)).thenReturn(Optional.of(item));
        when(attachmentRepository.countByFactoryIdAndEntityTypeAndEntityId(
                "F006", Attachment.EntityType.CUSTOMER_SUPPLIED_RECEIPT, "task-qa-a15")).thenReturn(1L);
        when(rawMaterialTypeRepository.findById("material-qa")).thenReturn(Optional.of(material));
        when(materialBatchRepository.saveAndFlush(any(MaterialBatch.class))).thenAnswer(inv -> inv.getArgument(0));
        com.cretas.aims.service.unit.CanonicalUnit kg = new com.cretas.aims.service.unit.CanonicalUnit(
                "kg", com.cretas.aims.service.unit.UnitDimension.MASS, "kg", BigDecimal.ONE, "千克", 4,
                java.util.Set.of(com.cretas.aims.service.unit.UnitUsageScope.INVENTORY_QUANTITY), "kg", true);
        when(unitContractService.normalize("F006", "kg"))
                .thenReturn(new com.cretas.aims.service.unit.UnitNormalizationResult("kg", "kg", kg));
        when(unitContractService.supportsUsage("F006", "kg",
                com.cretas.aims.service.unit.UnitUsageScope.INVENTORY_QUANTITY)).thenReturn(true);
    }

    private void limit(String rate) {
        when(overReceivePolicy.effectiveLimit("F006")).thenReturn(rate == null
                ? OverReceiveLimit.unlimited(OverReceiveLimit.SOURCE_UNCONFIGURED)
                : OverReceiveLimit.of(new BigDecimal(rate), OverReceiveLimit.SOURCE_FACTORY_SETTINGS));
    }

    private Throwable receive(String quantity) {
        CustomerSuppliedMaterialReceiptRequest request = new CustomerSuppliedMaterialReceiptRequest();
        request.setIdempotencyKey("qa-a15-" + quantity);
        request.setReceivedQuantity(new BigDecimal(quantity));
        try {
            service.receive("F006", "task-qa-a15", request, 1309L);
            return null;
        } catch (Throwable t) {
            return t;
        }
    }

    @Test
    @DisplayName("采购超收上限 10%: 应收 10 已收 4, 再收 7 (累计 11) → 放行, 任务完成, 累计 11")
    void withinTheSharedPurchaseLimitIsReceived() {
        limit("0.10");
        assertThat(receive("7")).isNull();
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("11");
        assertThat(requirement.getStatus()).isEqualTo(SalesOrderSuppliedMaterialRequirementStatus.COMPLETED);
        verify(overReceivePolicy).effectiveLimit("F006");
    }

    @Test
    @DisplayName("采购超收上限 10%: 再收 7.01 (累计 11.01) → 409, 文案带上限与本次最多, 带 hint, 一行不写")
    void beyondTheSharedLimitIsRejectedWithHint() {
        limit("0.10");
        BusinessException blocked = catchThrowableOfType(() -> {
            Throwable t = receive("7.01");
            if (t != null) throw t;
        }, BusinessException.class);
        assertThat(blocked).isNotNull();
        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getErrorCode()).isEqualTo("CUSTOMER_SUPPLIED_RECEIPT_EXCEEDS_REMAINING");
        assertThat(blocked.getMessage()).contains("含 10% 超收").contains("本次最多 7kg");
        assertThat(blocked.getActionHint()).isNotBlank();
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("4");
        verify(materialBatchRepository, never()).saveAndFlush(any());
        verify(requirementRepository, never()).save(any());
    }

    @Test
    @DisplayName("阴性对照: 工厂把采购超收配成 0 → 客供料也一克都不许多 (与采购同口径, 0 不是「不限制」)")
    void zeroLimitBlocksAnyExcess() {
        limit("0");
        assertThat(receive("6")).isNull();
        requirement.setReceivedQuantity(new BigDecimal("4"));
        requirement.setStatus(SalesOrderSuppliedMaterialRequirementStatus.PARTIALLY_RECEIVED);
        assertThat(receive("6.001")).isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("采购超收没人配过 (不限制) → 客供料同样不限制")
    void unconfiguredMeansUnlimitedLikePurchase() {
        limit(null);
        assertThat(receive("50")).isNull();
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("54");
    }

    // ---------------------------------------------------------------- 开关关 (默认) = 原行为

    @Test
    @DisplayName("开关关 (默认): 采购超收配了 10% 也不管用 —— 再收 6.01 (超剩余 6) 就拦, 且根本不去读采购上限")
    void switchOffKeepsZeroToleranceEvenWhenPurchaseAllowsMore() {
        when(disciplinePolicy.customerSuppliedOverReceiveAllowed("F006")).thenReturn(false);
        limit("0.10");

        Throwable blocked = receive("6.01");

        assertThat(blocked).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) blocked).getErrorCode()).isEqualTo("CUSTOMER_SUPPLIED_RECEIPT_EXCEEDS_REMAINING");
        assertThat(((BusinessException) blocked).getMessage()).isEqualTo("本次实收数量超过任务剩余待收数量");
        assertThat(((BusinessException) blocked).getActionHint()).contains("剩余待收 6").contains("客供料允许超收");
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("4");
        verify(overReceivePolicy, never()).effectiveLimit(any());
        verify(materialBatchRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("开关关: 恰好收满剩余 (6) 照常入库, 任务完成 —— 关的是「超收」, 不是收货")
    void switchOffStillReceivesUpToTheRemaining() {
        when(disciplinePolicy.customerSuppliedOverReceiveAllowed("F006")).thenReturn(false);
        limit(null);

        assertThat(receive("6")).isNull();
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("10");
        assertThat(requirement.getStatus()).isEqualTo(SalesOrderSuppliedMaterialRequirementStatus.COMPLETED);
    }

    @Test
    @DisplayName("开关关 + 采购不限制 (LIUSHANMEN 现状): 仍是多 1 克就拦 —— 默认关时上线后行为不变")
    void switchOffWithUnlimitedPurchaseStillBlocks() {
        when(disciplinePolicy.customerSuppliedOverReceiveAllowed("F006")).thenReturn(false);
        limit(null);

        assertThat(receive("6.01")).isInstanceOf(BusinessException.class);
        assertThat(requirement.getReceivedQuantity()).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("开关读不到 (阈值中心出错) → 按关 (更严), 不是 500, 也不放宽")
    void unreadableSwitchFailsClosed() {
        when(disciplinePolicy.customerSuppliedOverReceiveAllowed("F006")).thenThrow(new IllegalStateException("db down"));
        limit(null);

        Throwable blocked = receive("6.01");
        assertThat(blocked).isInstanceOf(BusinessException.class);
        assertThat(((BusinessException) blocked).getCode()).isEqualTo(409);
    }

    @Test
    @DisplayName("开关的读法: 只有显式 1 才开; 0 / 2 / 没配 (兜底 0) 都是关; 读的是 inventory.customer_supplied.allow_over_receive")
    void switchIsOnlyOnForAnExplicitOne() {
        com.cretas.aims.service.canvas.ThresholdResolverService hub =
                org.mockito.Mockito.mock(com.cretas.aims.service.canvas.ThresholdResolverService.class);
        com.cretas.aims.service.discipline.FactoryDisciplinePolicy real =
                new com.cretas.aims.service.discipline.FactoryDisciplinePolicy(hub);
        String key = com.cretas.aims.service.canvas.ThresholdKeys.INVENTORY_CUSTOMER_SUPPLIED_ALLOW_OVER_RECEIVE;
        assertThat(key).isEqualTo("inventory.customer_supplied.allow_over_receive");
        assertThat(com.cretas.aims.service.canvas.ThresholdKeys.GATING_KEYS).contains(key);

        when(hub.getInteger("F006", key, 0)).thenReturn(1);
        assertThat(real.customerSuppliedOverReceiveAllowed("F006")).isTrue();
        when(hub.getInteger("F006", key, 0)).thenReturn(0);
        assertThat(real.customerSuppliedOverReceiveAllowed("F006")).isFalse();
        when(hub.getInteger("F006", key, 0)).thenReturn(2);
        assertThat(real.customerSuppliedOverReceiveAllowed("F006")).isFalse();
    }
}
