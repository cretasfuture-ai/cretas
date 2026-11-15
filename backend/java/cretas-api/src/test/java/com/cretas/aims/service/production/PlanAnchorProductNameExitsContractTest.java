package com.cretas.aims.service.production;

import com.cretas.aims.controller.ProcessCheckinController;
import com.cretas.aims.controller.ProductionAnalyticsController;
import com.cretas.aims.controller.ProductionPlanController;
import com.cretas.aims.dto.production.ProductionPlanDTO;
import com.cretas.aims.dto.production.ProductionPlanImportDTO;
import com.cretas.aims.dto.scheduling.AffectedPlanDTO;
import com.cretas.aims.entity.ProductType;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.enums.ProductionPlanStatus;
import com.cretas.aims.mapper.ProductionPlanMapper;
import com.cretas.aims.repository.ConversionRepository;
import com.cretas.aims.repository.MaterialBatchRepository;
import com.cretas.aims.repository.MaterialConsumptionRepository;
import com.cretas.aims.repository.ProductTypeRepository;
import com.cretas.aims.repository.ProductionBatchRepository;
import com.cretas.aims.repository.ProductionPlanBatchUsageRepository;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.UserRepository;
import com.cretas.aims.repository.inventory.SalesOrderItemRepository;
import com.cretas.aims.repository.inventory.SalesOrderRepository;
import com.cretas.aims.service.SchedulingService;
import com.cretas.aims.service.impl.ImpactAnalysisServiceImpl;
import com.cretas.aims.service.impl.ProductionPlanServiceImpl;
import com.cretas.aims.utils.ExcelUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 联产计划的 productType 是内部原料锚点 ({@code __RAW_WORKFLOW_OWNER__<原料id>}),
 * 用户能看到的每个出口都不许把它原样输出 —— 与交货预警同一根因 (DeliveryWarningsProductNameContractTest)。
 * 每个出口一条断言, 跑在产品真实的出口方法上; 另各配「普通产品照旧」阳性对照。
 *
 * <p>控制器/ImpactAnalysis 用 Mockito 无构造实例 + CALLS_REAL_METHODS 直接跑真实出口方法, 只注入它读的字段。
 */
class PlanAnchorProductNameExitsContractTest {

    private static final String F = "F006";
    private static final String RAW_ID = "RMT_F006_LSM_2bce8bda718ed689";
    private static final String PLACEHOLDER = "__RAW_WORKFLOW_OWNER__";
    private static final String RAW_NAME = "LY-FX-安格斯65碎肉";

    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final ProductionPlanRepository plans = mock(ProductionPlanRepository.class);

    @BeforeEach
    void rawMaterialExists() {
        RawMaterialType r = new RawMaterialType();
        r.setId(RAW_ID);
        r.setName(RAW_NAME);
        when(materials.findByIdAndFactoryId(RAW_ID, F)).thenReturn(Optional.of(r));
    }

    private static ProductType product(String id, String name) {
        ProductType p = new ProductType();
        p.setId(id);
        p.setName(name);
        return p;
    }

    private static ProductionPlan plan(ProductType pt) {
        ProductionPlan p = new ProductionPlan();
        p.setId("PP-1");
        p.setPlanNumber("PLAN-1");
        p.setFactoryId(F);
        p.setProductTypeId(pt.getId());
        p.setProductType(pt);
        p.setPlannedQuantity(new BigDecimal("10"));
        p.setStatus(ProductionPlanStatus.IN_PROGRESS);
        p.setProcessName("切割");
        p.setEstimatedLaborCost(new BigDecimal("1"));
        return p;
    }

    private ProductType anchor() {
        return product(RAW_ID, PLACEHOLDER + RAW_ID);
    }

    private static <T> T realMethods(Class<T> type) {
        return Mockito.mock(type, Mockito.withSettings().defaultAnswer(Mockito.CALLS_REAL_METHODS));
    }

    // ---- ProductionPlanMapper#toDTO (计划列表/详情) ----
    @Test
    void mapperToDtoShowsRawMaterialName() {
        ProductionPlanMapper mapper = new ProductionPlanMapper();
        ReflectionTestUtils.setField(mapper, "rawMaterialTypeRepository", materials);

        ProductionPlanDTO dto = mapper.toDTO(plan(anchor()));
        assertThat(dto.getProductName()).isEqualTo(RAW_NAME);
        assertThat(dto.getProductTypeName()).isEqualTo(RAW_NAME);

        ProductionPlanDTO ordinary = mapper.toDTO(plan(product("SKU-1", "卤牛肉")));
        assertThat(ordinary.getProductName()).as("阳性对照").isEqualTo("卤牛肉");
    }

    @Test
    void mapperWithoutRepositoryStillNeverLeaksThePrefix() {
        ProductionPlanDTO dto = new ProductionPlanMapper().toDTO(plan(anchor()));
        assertThat(dto.getProductName()).doesNotContain(PLACEHOLDER);
    }

    // ---- ProductionPlanController#getTodayCards (今日看板卡片) ----
    @Test
    void todayCardsShowRawMaterialName() {
        ProductionPlanController c = realMethods(ProductionPlanController.class);
        ReflectionTestUtils.setField(c, "planRepository", plans);
        ReflectionTestUtils.setField(c, "rawMaterialTypeRepository", materials);
        when(plans.findByFactoryId(F)).thenReturn(List.of(plan(anchor()), plan(product("SKU-1", "卤牛肉"))));

        List<Map<String, Object>> cards = c.getTodayCards(F).getData();
        assertThat(cards).extracting(m -> m.get("productName")).containsExactlyInAnyOrder(RAW_NAME, "卤牛肉");
    }

    // ---- ProcessCheckinController#getAvailableProcesses (报工工序下拉) ----
    @Test
    void availableProcessesShowRawMaterialName() {
        ProcessCheckinController c = realMethods(ProcessCheckinController.class);
        ReflectionTestUtils.setField(c, "planRepository", plans);
        ReflectionTestUtils.setField(c, "rawMaterialTypeRepository", materials);
        when(plans.findByFactoryId(F)).thenReturn(List.of(plan(anchor()), plan(product("SKU-1", "卤牛肉"))));

        List<Map<String, Object>> rows = c.getAvailableProcesses(F).getData();
        assertThat(rows).extracting(m -> m.get("productName")).containsExactlyInAnyOrder(RAW_NAME, "卤牛肉");
    }

    // ---- ProductionAnalyticsController#getBudgetVsActual (预算 vs 实际) ----
    @Test
    void budgetVsActualShowsRawMaterialName() {
        ProductionAnalyticsController c = realMethods(ProductionAnalyticsController.class);
        ReflectionTestUtils.setField(c, "planRepository", plans);
        ReflectionTestUtils.setField(c, "rawMaterialTypeRepository", materials);
        when(plans.findByFactoryId(F)).thenReturn(List.of(plan(anchor()), plan(product("SKU-1", "卤牛肉"))));

        List<Map<String, Object>> rows = c.getBudgetVsActual(F, null, null, null).getData();
        assertThat(rows).extracting(m -> m.get("productName")).containsExactlyInAnyOrder(RAW_NAME, "卤牛肉");
    }

    // ---- ImpactAnalysisServiceImpl 受影响计划 (convertToAffectedPlan 是该服务唯一的构造出口, 私有, 经反射调) ----
    @Test
    void impactAnalysisAffectedPlanShowsRawMaterialName() {
        ImpactAnalysisServiceImpl s = realMethods(ImpactAnalysisServiceImpl.class);
        ReflectionTestUtils.setField(s, "rawMaterialTypeRepository", materials);

        AffectedPlanDTO dto = ReflectionTestUtils.invokeMethod(s, "convertToAffectedPlan", plan(anchor()), 30, 1);
        assertThat(dto.getProductName()).isEqualTo(RAW_NAME);

        AffectedPlanDTO ordinary = ReflectionTestUtils.invokeMethod(s, "convertToAffectedPlan",
                plan(product("SKU-1", "卤牛肉")), 30, 1);
        assertThat(ordinary.getProductName()).as("阳性对照").isEqualTo("卤牛肉");
    }

    // ---- ProductionPlanServiceImpl#toImportDTO (计划导出; 私有, 经反射调) ----
    @Test
    void exportRowShowsRawMaterialName() {
        ProductionPlanServiceImpl service = new ProductionPlanServiceImpl(plans, mock(ProductionBatchRepository.class),
                mock(MaterialBatchRepository.class), mock(MaterialConsumptionRepository.class),
                mock(ProductionPlanBatchUsageRepository.class), mock(ProductTypeRepository.class),
                mock(ProductionPlanMapper.class), mock(ConversionRepository.class),
                mock(SchedulingService.class), mock(com.cretas.aims.repository.ProductionLineRepository.class),
                mock(UserRepository.class), mock(ExcelUtil.class), mock(SalesOrderRepository.class),
                mock(SalesOrderItemRepository.class));
        ReflectionTestUtils.setField(service, "rawMaterialTypeRepository", materials);

        ProductionPlanImportDTO row = ReflectionTestUtils.invokeMethod(service, "toImportDTO", plan(anchor()));
        assertThat(row.getProductName()).isEqualTo(RAW_NAME);

        ProductionPlanImportDTO ordinary = ReflectionTestUtils.invokeMethod(service, "toImportDTO",
                plan(product("SKU-1", "卤牛肉")));
        assertThat(ordinary.getProductName()).as("阳性对照").isEqualTo("卤牛肉");
    }
}
