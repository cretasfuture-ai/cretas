package com.cretas.aims.service.production;

import com.cretas.aims.dto.production.ProductionPlanMaterialAdvisoryDTO;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 缺陷49 (owner 结单弹窗现场实测, 2026-09-02) —— #495 上线后 prod 实测: 计划列表顶部
 * advisory (scanMaterialAdvisory -> resolveAvailableRawStock) 报「L1纸箱可用0」, 同屏结单
 * 行级分布(#499, 镜像 ensureMaterialBatchAllowedForSettlement 的 RAW/LOGISTICS/WORKSHOP
 * 三类仓)却显示「主仓40 · 生产仓10」——顶部与行级自相矛盾(形态D)。
 *
 * <p>实测直查 cretas_prod_db 确认根因: 该计划 material_supply_mode=CUSTOMER_SUPPLIED,
 * L1纸箱两条批次(主仓40/生产仓10)ownership 全是 COMPANY_OWNED —— resolveAvailableRawStock
 * 的 CUSTOMER_SUPPLIED 分支只查 CUSTOMER_OWNED 批次, 从未考虑公司自有库存, 即使
 * 缺陷38 已经在结单/领料写路径上放行「辅料/包材可用工厂自有库存」。
 *
 * <p>本测试钉住: CUSTOMER_SUPPLIED 计划下, PACKAGING/AUXILIARY 物料的可用量必须把
 * COMPANY_OWNED 库存也算进去(与结单同一个 isCompanyStockEligibleCategory 口径), RAW 物料
 * 仍然只能用客供库存(缺陷38 逐字不变)。
 */
class MaterialAdvisoryCustomerSuppliedCompanyStockTest {

    private static final String F = "LIUSHANMEN";
    private static final String PRODUCT = "PT_TEST_HANBAOBING";
    private static final String L1_BOX = "RMT_L1_BOX";
    private static final String RAW_MAT = "RMT_RAW_A";
    private static final String CUSTOMER = "CUST-001";

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;
    private RawMaterialTypeRepository materialTypeRepo;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        BomRecipeItem l1Box = new BomRecipeItem();
        l1Box.setMaterialTypeId(L1_BOX);
        l1Box.setMaterialName("L1纸箱");
        l1Box.setMaterialCategory("PACKAGING");
        l1Box.setStandardQuantity(new BigDecimal("0.0694"));
        l1Box.setYieldRate(new BigDecimal("100.00"));
        l1Box.setUnit("个");

        BomRecipeItemRepository bomRepo = mock(BomRecipeItemRepository.class);
        when(bomRepo.findCurrentByProduct(eq(F), eq(PRODUCT))).thenReturn(List.of(l1Box));

        batchRepo = mock(MaterialBatchRepository.class);
        when(batchRepo.findRawStockUnitsByMaterialType(anyString(), anyString())).thenReturn(List.of("个"));
        // 客供库存查询恒 0 (这条计划从未收过客供 L1纸箱, 只有工厂自有库存)
        when(batchRepo.sumAvailableUnassignedCustomerOwnedRawStock(eq(F), anyString(), eq(CUSTOMER)))
                .thenReturn(BigDecimal.ZERO);
        when(batchRepo.sumAvailableCustomerSuppliedRawStock(eq(F), anyString(), eq(CUSTOMER), anyString()))
                .thenReturn(BigDecimal.ZERO);

        materialTypeRepo = mock(RawMaterialTypeRepository.class);

        ReflectionTestUtils.setField(service, "bomRecipeItemRepository", bomRepo);
        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", materialTypeRepo);
    }

    private static RawMaterialType materialType(String mainCategory) {
        RawMaterialType mt = new RawMaterialType();
        mt.setMainCategory(mainCategory);
        return mt;
    }

    private static ProductionPlan customerSuppliedPlan() {
        ProductionPlan p = new ProductionPlan();
        p.setId("plan-cs");
        p.setPlanNumber("PLAN-CS");
        p.setFactoryId(F);
        p.setProductTypeId(PRODUCT);
        p.setPlannedQuantity(new BigDecimal("100"));
        p.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        p.setCustomerId(CUSTOMER);
        // sourceOrderId 留空 -> 走 sumAvailableUnassignedCustomerOwnedRawStock 分支
        return p;
    }

    @SuppressWarnings("unchecked")
    private BigDecimal available() {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "resolveAvailableRawStock", F, customerSuppliedPlan(), L1_BOX);
    }

    @Test
    @DisplayName("阳性对照: rawMaterialTypeRepository 真的被咨询了 mainCategory (否则下面全是恒真式)")
    void positiveControlMainCategoryIsConsulted() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材")));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(new BigDecimal("50"));
        available();
        org.mockito.Mockito.verify(materialTypeRepo, org.mockito.Mockito.atLeastOnce())
                .findByIdAndFactoryId(eq(L1_BOX), eq(F));
    }

    @Test
    @DisplayName("🔴 主断言: CUSTOMER_SUPPLIED计划下, PACKAGING物料的公司自有库存(主仓40+生产仓10=50)必须被算进可用量")
    void companyOwnedStockCountsForPackagingUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材")));
        // sumAvailableRawStockQuantityByMaterialType 现在已限定 RAW/LOGISTICS/WORKSHOP 三类仓,
        // 主仓(RAW/LOGISTICS)40 + 生产仓(WORKSHOP)10 = 50 (SQL 层合并, 这里直接 mock 合计值)。
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(new BigDecimal("50"));

        BigDecimal available = available();
        assertEquals(0, available.compareTo(new BigDecimal("50")),
                "可用量应为 50 (主仓40+生产仓10), 实际 " + available);
    }

    @Test
    @DisplayName("🔴 阴性对照: 只在 FINISHED/RD/WIP 仓有货 ⇒ 仍算 0 (证明对齐到合法仓集合, 不是「任何仓」)")
    void stockInIneligibleWarehouseTypesStillReadsZero() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材")));
        // SQL 层已经把 FINISHED/RD/WIP 排除在外, 所以这类货不会被 mock 的这个方法返回 —— 模拟为 0。
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(BigDecimal.ZERO);

        BigDecimal available = available();
        assertEquals(0, available.compareTo(BigDecimal.ZERO),
                "只在 FINISHED/RD/WIP 仓有货时应仍算 0: " + available);
    }

    @Test
    @DisplayName("🔴 阴性对照: RAW 物料不受影响 —— CUSTOMER_SUPPLIED 计划下仍然只能用客供库存, 缺陷38逐字不变")
    void rawMaterialStaysCustomerScopedUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(RAW_MAT), eq(F)))
                .thenReturn(Optional.of(materialType("原料")));
        when(batchRepo.sumAvailableUnassignedCustomerOwnedRawStock(eq(F), eq(RAW_MAT), eq(CUSTOMER)))
                .thenReturn(BigDecimal.ZERO);
        // 即使公司仓里有货, RAW 也不该被算进来
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(RAW_MAT)))
                .thenReturn(new BigDecimal("999"));

        BigDecimal available = (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "resolveAvailableRawStock", F, customerSuppliedPlan(), RAW_MAT);
        assertEquals(0, available.compareTo(BigDecimal.ZERO),
                "RAW 物料在 CUSTOMER_SUPPLIED 计划下被公司库存污染了: " + available);
    }

    @Test
    @DisplayName("非 CUSTOMER_SUPPLIED 计划: 直接用限定仓类型后的公司库存查询, 行为不变")
    void nonCustomerSuppliedPlanUsesWarehouseScopedCompanyStock() {
        ProductionPlan normalPlan = new ProductionPlan();
        normalPlan.setId("plan-normal");
        normalPlan.setFactoryId(F);
        normalPlan.setProductTypeId(PRODUCT);
        normalPlan.setPlannedQuantity(new BigDecimal("100"));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialType(eq(F), eq(L1_BOX)))
                .thenReturn(new BigDecimal("50"));

        BigDecimal available = (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "resolveAvailableRawStock", F, normalPlan, L1_BOX);
        assertEquals(0, available.compareTo(new BigDecimal("50")), "实际 " + available);
    }
}
