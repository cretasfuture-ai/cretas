package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.MaterialSupplyMode;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.service.factory.WarehouseResolver;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * 缺陷38 系统性排查 (2026-09-02) —— {@code ProductionPlanServiceImpl#resolveWorkshopRawStock}
 * 原来对 CUSTOMER_SUPPLIED 计划<b>无条件</b>返回 null(跳过生产仓核对), 与
 * {@link MaterialAdvisoryCustomerSuppliedCompanyStockTest}(缺陷49, resolveAvailableRawStock)
 * 修复前同一形状: PACKAGING/AUXILIARY 本就该走工厂自有库存(isCompanyStockEligibleCategory),
 * 而 {@code sumAvailableRawStockQuantityByMaterialTypeAndWarehouse} 本来就只认 COMPANY_OWNED
 * —— 于是「全厂有货、但生产仓没货、忘了调拨」这条预警(NOT_IN_WORKSHOP)对 CUSTOMER_SUPPLIED
 * 计划下的辅料/包材<b>从未生效过</b>, 同一物料换到普通计划下却会正常预警。
 *
 * <p>本测试钉住: CUSTOMER_SUPPLIED 计划下, PACKAGING/AUXILIARY 物料仍然会核对生产仓库存,
 * RAW 物料继续整体跳过(那类走客供归属校验, 仓库维度的结论不适用, 缺陷38逐字不变)。
 */
class WorkshopRawStockCustomerSuppliedCompanyStockTest {

    private static final String F = "LIUSHANMEN";
    private static final String L1_BOX = "RMT_L1_BOX";
    private static final String RAW_MAT = "RMT_RAW_A";
    private static final String WORKSHOP_ID = "WH-WKS-ID";

    private ProductionPlanServiceImpl service;
    private MaterialBatchRepository batchRepo;
    private RawMaterialTypeRepository materialTypeRepo;
    private WarehouseResolver warehouseResolver;

    @BeforeEach
    void setUp() {
        service = mock(ProductionPlanServiceImpl.class, withSettings().defaultAnswer(CALLS_REAL_METHODS));

        batchRepo = mock(MaterialBatchRepository.class);
        materialTypeRepo = mock(RawMaterialTypeRepository.class);
        warehouseResolver = mock(WarehouseResolver.class);
        when(warehouseResolver.resolveWorkshopId(eq(F))).thenReturn(WORKSHOP_ID);

        ReflectionTestUtils.setField(service, "materialBatchRepository", batchRepo);
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", materialTypeRepo);
        ReflectionTestUtils.setField(service, "warehouseResolver", warehouseResolver);
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
        p.setMaterialSupplyMode(MaterialSupplyMode.CUSTOMER_SUPPLIED);
        p.setCustomerId("CUST-001");
        return p;
    }

    private BigDecimal workshopStock(ProductionPlan plan, String materialTypeId) {
        return (BigDecimal) ReflectionTestUtils.invokeMethod(
                service, "resolveWorkshopRawStock", F, plan, materialTypeId);
    }

    @Test
    @DisplayName("阳性对照: rawMaterialTypeRepository 真的被咨询了 mainCategory (否则下面全是恒真式)")
    void positiveControlMainCategoryIsConsulted() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材")));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), eq(L1_BOX), eq(WORKSHOP_ID)))
                .thenReturn(new BigDecimal("10"));

        workshopStock(customerSuppliedPlan(), L1_BOX);

        verify(materialTypeRepo, org.mockito.Mockito.atLeastOnce()).findByIdAndFactoryId(eq(L1_BOX), eq(F));
    }

    @Test
    @DisplayName("🔴 主断言: CUSTOMER_SUPPLIED计划下, PACKAGING物料必须核对生产仓公司自有库存")
    void companyOwnedWorkshopStockCountsForPackagingUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(L1_BOX), eq(F)))
                .thenReturn(Optional.of(materialType("包材")));
        when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), eq(L1_BOX), eq(WORKSHOP_ID)))
                .thenReturn(new BigDecimal("10"));

        BigDecimal workshop = workshopStock(customerSuppliedPlan(), L1_BOX);
        assertEquals(0, workshop.compareTo(new BigDecimal("10")),
                "PACKAGING 物料应核对生产仓公司库存, 实际 " + workshop);
    }

    @Test
    @DisplayName("🔴 阴性对照: RAW 物料不受影响 —— CUSTOMER_SUPPLIED计划下仍然整体跳过生产仓核对, 缺陷38逐字不变")
    void rawMaterialSkipsWorkshopCheckUnderCustomerSuppliedPlan() {
        when(materialTypeRepo.findByIdAndFactoryId(eq(RAW_MAT), eq(F)))
                .thenReturn(Optional.of(materialType("原料")));
        // 即使生产仓查询本身会返回值, RAW 也不该走到这一步
        when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), eq(RAW_MAT), eq(WORKSHOP_ID)))
                .thenReturn(new BigDecimal("999"));

        BigDecimal workshop = workshopStock(customerSuppliedPlan(), RAW_MAT);
        assertNull(workshop, "RAW 物料在 CUSTOMER_SUPPLIED 计划下应返回 null(跳过), 实际 " + workshop);
        verify(batchRepo, never())
                .sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), eq(RAW_MAT), anyString());
    }

    @Test
    @DisplayName("非 CUSTOMER_SUPPLIED 计划: 不咨询物料大类, 直接核对生产仓, 行为不变")
    void nonCustomerSuppliedPlanSkipsCategoryLookup() {
        ProductionPlan normalPlan = new ProductionPlan();
        normalPlan.setId("plan-normal");
        normalPlan.setFactoryId(F);
        when(batchRepo.sumAvailableRawStockQuantityByMaterialTypeAndWarehouse(eq(F), eq(L1_BOX), eq(WORKSHOP_ID)))
                .thenReturn(new BigDecimal("10"));

        BigDecimal workshop = workshopStock(normalPlan, L1_BOX);
        assertEquals(0, workshop.compareTo(new BigDecimal("10")), "实际 " + workshop);
        verify(materialTypeRepo, never()).findByIdAndFactoryId(anyString(), anyString());
    }
}
