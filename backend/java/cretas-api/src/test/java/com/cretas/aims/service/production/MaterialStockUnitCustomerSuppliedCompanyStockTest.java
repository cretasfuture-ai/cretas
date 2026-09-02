package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 缺陷38 系统性排查 (2026-09-02) —— {@code ProductionPlanServiceImpl#resolveMaterialStockUnit}
 * 是 {@link MaterialAdvisoryCustomerSuppliedCompanyStockTest}(缺陷49, resolveAvailableRawStock)
 * 修复前的同一形状: CUSTOMER_SUPPLIED 计划下, PACKAGING/AUXILIARY 走公司自有库存
 * (isCompanyStockEligibleCategory)时, 只查客供批次单位, 恒空, 于是回退到
 * {@code RawMaterialType.unit}(采购/展示标签, 如「箱」) 而不是公司批次真实的称重/计数单位
 * (如「个」) —— resolveAvailableRawStock 已经把公司库存数量算进「可用量」, 这里的单位却没跟上,
 * 于是「需要 X箱, 可用 50箱」这类提示用错的单位比较, 与领料/结单侧的真实单位口径自相矛盾。
 *
 * <p>本测试钉住: CUSTOMER_SUPPLIED 计划下, PACKAGING/AUXILIARY 物料的库存单位必须能读到公司
 * 自有批次的 {@code quantityUnit}(与结单同一个 isCompanyStockEligibleCategory 口径), RAW 物料
 * 仍然只能读客供批次单位(缺陷38逐字不变)。
 */
class MaterialStockUnitCustomerSuppliedCompanyStockTest {

    private static final String F = "LIUSHANMEN";
    private static final String L1_BOX = "RMT_L1_BOX";
    private static final String RAW_MAT = "RMT_RAW_A";
    private static final String CUSTOMER = "CUST-001";
    private static final String SOURCE_ORDER = "SO-001";

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;
    private RawMaterialTypeRepository materialTypeRepo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        batchRepo = mock(MaterialBatchRepository.class);
        materialTypeRepo = mock(RawMaterialTypeRepository.class);

        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", materialTypeRepo);
    }

    private static RawMaterialType materialType(String mainCategory, String purchaseUnit) {
        RawMaterialType mt = new RawMaterialType();
        mt.setMainCategory(mainCategory);
        mt.setUnit(purchaseUnit);
        return mt;
    }

    private static ProductionPlan customerSuppliedPlanWithSourceOrder() {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-cs");
        p.setPlanNumber("PLAN-CS");
        p.setFactoryId(F);
        p.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        p.setCustomerId(CUSTOMER);
        p.setSourceOrderId(SOURCE_ORDER);
        return p;
    }

    private String stockUnit(ProductionPlan plan, String materialTypeId) {
        return (String) ReflectionTestUtils.invokeMethod(
                service, "resolveMaterialStockUnit", F, plan, materialTypeId);
    }

    @Test
    @DisplayName("阳性对照: rawMaterialTypeRepository 真的被咨询了 mainCategory (否则下面全是恒真式)")
    void positiveControlMainCategoryIsConsulted() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材", "箱")));
        when(batchRepo.findCustomerSuppliedRawStockUnits(eq(F), eq(L1_BOX), eq(CUSTOMER), eq(SOURCE_ORDER)))
                .thenReturn(List.of());
        when(batchRepo.findRawStockUnitsByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(List.of("个"));

        stockUnit(customerSuppliedPlanWithSourceOrder(), L1_BOX);

        verify(materialTypeRepo, org.mockito.Mockito.atLeastOnce()).findByIdAndFactoryId(eq(L1_BOX), eq(F));
    }

    @Test
    @DisplayName("🔴 主断言: CUSTOMER_SUPPLIED计划下, PACKAGING物料无客供批次时必须读到公司批次的真实计量单位, 不能回退采购标签")
    void companyOwnedBatchUnitCountsForPackagingUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材", "箱")));
        // 这条计划从未收过客供 L1纸箱, 客供批次单位查询恒空
        when(batchRepo.findCustomerSuppliedRawStockUnits(eq(F), eq(L1_BOX), eq(CUSTOMER), eq(SOURCE_ORDER)))
                .thenReturn(List.of());
        // 公司自有批次的真实计量单位是「个」, 与 RawMaterialType.unit「箱」不同
        when(batchRepo.findRawStockUnitsByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(List.of("个"));

        String unit = stockUnit(customerSuppliedPlanWithSourceOrder(), L1_BOX);
        assertEquals("个", unit,
                "应读到公司批次真实单位「个」, 而不是回退到采购标签「箱」: 实际 " + unit);
    }

    @Test
    @DisplayName("🔴 阴性对照: RAW 物料不受影响 —— 客供批次为空时不查公司批次, 直接回退 RawMaterialType.unit, 缺陷38逐字不变")
    void rawMaterialStaysCustomerScopedUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(RAW_MAT), eq(F)))
                .thenReturn(Optional.of(materialType("原料", "箱")));
        // 兜底路径 (无可用批次时) 读的是 findById, 不是 findByIdAndFactoryId —— 两个都要桩
        when(materialTypeRepo.findById(eq(RAW_MAT)))
                .thenReturn(Optional.of(materialType("原料", "箱")));
        when(batchRepo.findCustomerSuppliedRawStockUnits(eq(F), eq(RAW_MAT), eq(CUSTOMER), eq(SOURCE_ORDER)))
                .thenReturn(List.of());
        // 即使公司仓里有货(单位「个」), RAW 也不该读到它
        when(batchRepo.findRawStockUnitsByMaterialType(eq(F), eq(RAW_MAT)))
                .thenReturn(List.of("个"));

        String unit = stockUnit(customerSuppliedPlanWithSourceOrder(), RAW_MAT);
        assertEquals("箱", unit,
                "RAW 物料应回退到 RawMaterialType.unit「箱」, 不应读到公司批次单位: 实际 " + unit);
        verify(batchRepo, org.mockito.Mockito.never())
                .findRawStockUnitsByMaterialType(eq(F), eq(RAW_MAT));
    }

    @Test
    @DisplayName("非 CUSTOMER_SUPPLIED 计划: 直接用公司批次单位查询, 行为不变")
    void nonCustomerSuppliedPlanUsesCompanyStockUnitsUnchanged() {
        ProductionPlan normalPlan = new ProductionPlan();
        normalPlan.setId("plan-normal");
        normalPlan.setFactoryId(F);
        when(batchRepo.findRawStockUnitsByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(List.of("个"));

        String unit = stockUnit(normalPlan, L1_BOX);
        assertEquals("个", unit, "实际 " + unit);
        verify(materialTypeRepo, org.mockito.Mockito.never()).findByIdAndFactoryId(anyString(), anyString());
    }

    @Test
    @DisplayName("CUSTOMER_SUPPLIED计划下, PACKAGING物料客供批次和公司批次都有货时, 两侧单位都会被查询到(合并候选)")
    void customerAndCompanyUnitsAreBothConsultedForPackaging() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材", "箱")));
        when(batchRepo.findCustomerSuppliedRawStockUnits(eq(F), eq(L1_BOX), eq(CUSTOMER), eq(SOURCE_ORDER)))
                .thenReturn(List.of("个"));
        when(batchRepo.findRawStockUnitsByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(List.of("个"));

        String unit = stockUnit(customerSuppliedPlanWithSourceOrder(), L1_BOX);
        assertEquals("个", unit, "实际 " + unit);
        verify(batchRepo, org.mockito.Mockito.atLeastOnce())
                .findCustomerSuppliedRawStockUnits(eq(F), eq(L1_BOX), eq(CUSTOMER), eq(SOURCE_ORDER));
        verify(batchRepo, org.mockito.Mockito.atLeastOnce())
                .findRawStockUnitsByMaterialType(eq(F), eq(L1_BOX));
    }
}
