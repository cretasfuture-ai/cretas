package com.cretas.aims.controller;

import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.factory.FactoryMaterialRequisition;
import com.cretas.aims.entity.factory.FactoryMaterialRequisitionItem;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.security.PriceMaskResolver;
import com.cretas.aims.service.ProductionPlanService;
import com.cretas.aims.service.factory.FactoryMaterialRequisitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺陷 49 — 配料单（按锅配料）打印: 跨批次汇总把「整计划总需求」当成「每张单新增量」累加,
 * 让同一物料在计划下有 2 张(及以上)领料单时，总需求被乘以领料单数量。
 *
 * <p>实测(2026-09-02, LIUSHANMEN plan PLAN-1788280683352-34F8CD82, planned 100kg 熟肉饼):
 * L1纸箱(PACKAGING, BOM standardQuantity 0.0694) 有 2 张领料单 MR20260902-0003 / -0005,
 * 各自的 requiredQty 都是 6.94（= 0.0694 × 100, 由 FactoryMaterialRequisitionServiceImpl
 * .generateFromPlan 用**计划总量**算出, 不是"这批新增的量"）。旧的
 * {@link PrintController#aggregateMaterialRequirementRows} 把 requiredQty 当"跨单据可累加
 * 的事件流水"一样 sum, 印成 13.88 —— 而 ProductionPlanServiceImpl#scanMaterialAdvisory
 * (「缺料预警」用的同一份口径: perUnitRequired.multiply(plannedQty)) 从不读
 * factory_material_requisitions, 恒算出 6.94。
 *
 * <p>本测试直接测 private builder (与 {@link PrintControllerSp12T8Test} 同一手法,
 * via reflection, 不启动 Spring 容器)。
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("缺陷 49 — PrintController.buildBatchingSheetPayload 配料单跨单据物料需求不应加倍")
class PrintControllerBatchingSheetTest {

    private PrintController controller;
    private ProductionPlanService productionPlanService;
    private FactoryMaterialRequisitionService factoryMaterialRequisitionService;
    private ProductTypeRepository productTypeRepository;

    @BeforeEach
    void setUp() {
        productionPlanService = mock(ProductionPlanService.class);
        factoryMaterialRequisitionService = mock(FactoryMaterialRequisitionService.class);
        productTypeRepository = mock(ProductTypeRepository.class);

        controller = new PrintController(
                mock(RestTemplate.class),
                "http://localhost:8083",
                mock(PriceMaskResolver.class));
        ReflectionTestUtils.setField(controller, "productionPlanService", productionPlanService);
        ReflectionTestUtils.setField(controller, "factoryMaterialRequisitionService",
                factoryMaterialRequisitionService);
        ReflectionTestUtils.setField(controller, "productTypeRepository", productTypeRepository);
    }

    private FactoryMaterialRequisitionItem packagingItem(BigDecimal requiredQty,
            BigDecimal pickedQty, BigDecimal issuedQty) {
        FactoryMaterialRequisitionItem it = new FactoryMaterialRequisitionItem();
        it.setMaterialTypeId("RMT_1782891392801");
        it.setMaterialName("L1纸箱");
        it.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.PACKAGING);
        it.setRequiredQty(requiredQty);
        it.setPickedQty(pickedQty);
        it.setIssuedQty(issuedQty);
        it.setUnit("个");
        return it;
    }

    // ==================== BS49-1: 主复现 — LIUSHANMEN 实拍数据形状 ====================

    @Test
    @DisplayName("BS49-1: 2 张领料单各自 requiredQty=6.94(整计划总量) ⇒ 配料单印 6.94, 不是 13.88")
    void buildBatchingSheetPayload_twoRequisitionsSameWholePlanQty_notDoubled() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-1788280683352-34F8CD82");
        plan.setProductName("熟肉饼");
        plan.setProductTypeId("PT-SHOUROU");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        when(productionPlanService.getProductionPlanById("LIUSHANMEN", "plan-shourou"))
                .thenReturn(plan);

        ProductType pt = new ProductType();
        pt.setId("PT-SHOUROU");
        pt.setFactoryId("LIUSHANMEN");
        pt.setUnit("kg");
        pt.setSinglePotCapacity(new BigDecimal("20"));
        when(productTypeRepository.findByIdAndFactoryId("PT-SHOUROU", "LIUSHANMEN"))
                .thenReturn(Optional.of(pt));

        // 两张真实领料单 (镜像 prod MR20260902-0003 / MR20260902-0005), 各自独立算出
        // L1纸箱 requiredQty=6.94 —— generateFromPlan 每次都用计划总量重新算, 不是增量。
        FactoryMaterialRequisitionItem carton1 =
                packagingItem(new BigDecimal("6.940"), new BigDecimal("0.000"), new BigDecimal("0.000"));
        FactoryMaterialRequisition req1 = mock(FactoryMaterialRequisition.class);
        when(req1.getItems()).thenReturn(List.of(carton1));

        FactoryMaterialRequisitionItem carton2 =
                packagingItem(new BigDecimal("6.940"), new BigDecimal("10.000"), new BigDecimal("10.000"));
        FactoryMaterialRequisition req2 = mock(FactoryMaterialRequisition.class);
        when(req2.getItems()).thenReturn(List.of(carton2));

        when(factoryMaterialRequisitionService.listByPlan("LIUSHANMEN", "plan-shourou"))
                .thenReturn(List.of(req1, req2));

        Map<String, Object> payload = invokeBuildBatchingSheetPayload("LIUSHANMEN", "plan-shourou", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");
        Map<String, Object> cartonRow = items.stream()
                .filter(r -> "L1纸箱".equals(r.get("materialName")))
                .findFirst().orElseThrow(() -> new AssertionError("L1纸箱 row not found"));

        // ✅ 判据: 与 ProductionPlanServiceImpl#scanMaterialAdvisory 同一口径的正确值 6.94,
        // ⛔ 不是旧 bug 的 13.88 (= 6.94 × 2 张单)。
        assertThat(cartonRow.get("totalQty")).isEqualTo("6.94");
        assertThat(cartonRow.get("plannedAuxiliaryQty")).isEqualTo("6.94");

        // ✅ 操作性事件量 (已拣/已发) 仍然应该跨单据累加 —— 这两张单是真实发生的两次拣发,
        // 不是同一个数字重复算出来的, 我的修法不该动这部分。
        assertThat(cartonRow.get("plannedIssueQty")).isEqualTo("10");   // pickedQty: 0 + 10
        assertThat(cartonRow.get("deliveredQty")).isEqualTo("10");      // issuedQty: 0 + 10
    }

    // ==================== BS49-2: 阴性对照 — 只在一张单里出现的物料不受影响 ====================

    @Test
    @DisplayName("BS49-2 阴性对照: 只在一张领料单出现的物料 totalQty 不变 (证明是去重不是砍半)")
    void buildBatchingSheetPayload_materialInOnlyOneRequisition_unchanged() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-SINGLE-SOURCE");
        plan.setProductName("熟肉饼");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        when(productionPlanService.getProductionPlanById("LIUSHANMEN", "plan-single"))
                .thenReturn(plan);

        FactoryMaterialRequisitionItem carton =
                packagingItem(new BigDecimal("6.940"), new BigDecimal("10.000"), new BigDecimal("10.000"));
        FactoryMaterialRequisitionItem saltOnlyInReq1 = new FactoryMaterialRequisitionItem();
        saltOnlyInReq1.setMaterialTypeId("mat-salt");
        saltOnlyInReq1.setMaterialName("食盐");
        saltOnlyInReq1.setMaterialCategory(FactoryMaterialRequisitionItem.MaterialCategory.AUXILIARY);
        saltOnlyInReq1.setRequiredQty(new BigDecimal("3.200"));
        saltOnlyInReq1.setUnit("kg");

        FactoryMaterialRequisition req1 = mock(FactoryMaterialRequisition.class);
        when(req1.getItems()).thenReturn(List.of(carton, saltOnlyInReq1));

        FactoryMaterialRequisitionItem carton2 =
                packagingItem(new BigDecimal("6.940"), new BigDecimal("0.000"), new BigDecimal("0.000"));
        FactoryMaterialRequisition req2 = mock(FactoryMaterialRequisition.class);
        when(req2.getItems()).thenReturn(List.of(carton2));  // 食盐不在这张单里

        when(factoryMaterialRequisitionService.listByPlan("LIUSHANMEN", "plan-single"))
                .thenReturn(List.of(req1, req2));

        Map<String, Object> payload = invokeBuildBatchingSheetPayload("LIUSHANMEN", "plan-single", null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) payload.get("items");

        Map<String, Object> saltRow = items.stream()
                .filter(r -> "食盐".equals(r.get("materialName")))
                .findFirst().orElseThrow(() -> new AssertionError("食盐 row not found"));
        // ⛔ 判据反过来也要成立: 只在一张单出现的物料, 值必须原样保留 3.2, 不能被我的
        // 「去重逻辑」误伤成 1.6 (证明这是去重不是全体砍半)。
        assertThat(saltRow.get("plannedAuxiliaryQty")).isEqualTo("3.2");

        Map<String, Object> cartonRow = items.stream()
                .filter(r -> "L1纸箱".equals(r.get("materialName")))
                .findFirst().orElseThrow(() -> new AssertionError("L1纸箱 row not found"));
        assertThat(cartonRow.get("totalQty")).isEqualTo("6.94");
    }

    // ==================== BS49-3: 诚实文案的前置条件保留 ====================

    @Test
    @DisplayName("BS49-3: 产品未配置单锅产能 ⇒ potCount/singlePotCapacity 仍为 null (驱动模板显示诚实提示)")
    void buildBatchingSheetPayload_noSinglePotCapacity_potCountStaysNull() throws Exception {
        ProductionPlanDTO plan = new ProductionPlanDTO();
        plan.setPlanNumber("PLAN-NO-POT-CAP");
        plan.setProductName("熟肉饼");
        plan.setProductTypeId("PT-NO-CAP");
        plan.setPlannedQuantity(new BigDecimal("100"));
        plan.setPlannedUnit("kg");
        when(productionPlanService.getProductionPlanById("LIUSHANMEN", "plan-no-cap"))
                .thenReturn(plan);

        ProductType pt = new ProductType();
        pt.setId("PT-NO-CAP");
        pt.setFactoryId("LIUSHANMEN");
        pt.setUnit("kg");
        pt.setSinglePotCapacity(null);  // 未配置单锅产能
        when(productTypeRepository.findByIdAndFactoryId("PT-NO-CAP", "LIUSHANMEN"))
                .thenReturn(Optional.of(pt));

        when(factoryMaterialRequisitionService.listByPlan("LIUSHANMEN", "plan-no-cap"))
                .thenReturn(List.of());

        Map<String, Object> payload = invokeBuildBatchingSheetPayload("LIUSHANMEN", "plan-no-cap", null);

        // Java 侧契约: potCount=null (+ singlePotCapacity=null) 是驱动 Python 渲染层打印
        // 「请先在 产品管理 → 该产品 设置『单锅产能』」这句诚实提示的唯一开关
        // (pdf_renderer.py:812/863, PrintController 端不重复渲染文案)。
        // 我这次的修法只动 requiredQty 的累加逻辑, 断言这个开关没被连带改坏。
        assertThat(payload.get("potCount")).isNull();
        assertThat(payload.get("singlePotCapacity")).isNull();
    }

    // ==================== reflection helper ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> invokeBuildBatchingSheetPayload(
            String factoryId, String planId, Map<String, String> overrides) throws Exception {
        Method m = PrintController.class.getDeclaredMethod(
                "buildBatchingSheetPayload", String.class, String.class, Map.class);
        m.setAccessible(true);
        return (Map<String, Object>) m.invoke(controller, factoryId, planId, overrides);
    }
}
