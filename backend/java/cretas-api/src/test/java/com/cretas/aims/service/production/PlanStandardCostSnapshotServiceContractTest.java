package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.entity.RawMaterialType;
import com.cretas.aims.entity.bom.BomRecipe;
import com.cretas.aims.entity.bom.BomRecipeItem;
import com.cretas.aims.entity.production.PlanStandardCostLine;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.RawMaterialTypeRepository;
import com.cretas.aims.repository.bom.BomRecipeItemRepository;
import com.cretas.aims.repository.production.PlanStandardCostLineRepository;
import com.cretas.aims.service.workflow.PlanInputShareResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 缺口 09: 快照取「开计划那一刻」物料档案的未税单价; ⛔ 不用近期采购均价 (moving_avg_price);
 * 档案没价就记 null (报表说无法比较), 不猜。标准用量规则只取计划钉住的配方。
 */
class PlanStandardCostSnapshotServiceContractTest {

    private final BomRecipeItemRepository items = mock(BomRecipeItemRepository.class);
    private final RawMaterialTypeRepository materials = mock(RawMaterialTypeRepository.class);
    private final PlanInputShareResolver shares = mock(PlanInputShareResolver.class);
    private final PlanStandardCostSnapshotService service = new PlanStandardCostSnapshotService(
            mock(PlanStandardCostLineRepository.class), items, materials,
            mock(ProductionPlanRepository.class), mock(PlatformTransactionManager.class));

    private static RawMaterialType material(String id, String unitPrice, String movingAvg) {
        RawMaterialType m = new RawMaterialType();
        m.setId(id);
        m.setName("料-" + id);
        m.setUnit("kg");
        m.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        m.setMovingAvgPrice(movingAvg == null ? null : new BigDecimal(movingAvg));
        return m;
    }

    private static BomRecipeItem item(BomRecipe recipe, String materialId, String category, String stdQty, String port) {
        BomRecipeItem i = new BomRecipeItem();
        i.setRecipe(recipe);
        i.setMaterialTypeId(materialId);
        i.setMaterialName("BOM-" + materialId);
        i.setMaterialCategory(category);
        i.setStandardQuantity(stdQty == null ? null : new BigDecimal(stdQty));
        i.setYieldRate(new BigDecimal("100"));
        i.setUnit("kg");
        i.setWorkflowInputPortId(port);
        return i;
    }

    @Test
    void snapshotsMaterialMasterUnitPriceAndNeverTheMovingAverage() {
        BomRecipe recipe = new BomRecipe();
        recipe.setId("R1");
        recipe.setVersion(3);
        recipe.setProductTypeId("SKU");
        recipe.setOverallYieldRate(new BigDecimal("80"));
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-1");
        plan.setProductTypeId("SKU");
        Map<String, String> pinned = new LinkedHashMap<>();
        pinned.put("SKU", "R1");
        plan.setSelectedBomRecipeIdsByProduct(pinned);

        when(items.findByRecipeIdsWithRecipe(eq("F006"), any())).thenReturn(List.of(
                item(recipe, "BEEF", "RAW", null, "port-beef"),
                item(recipe, "BOX", "PACKAGING", "1", null)));
        when(materials.findByIdAndFactoryId("BEEF", "F006")).thenReturn(Optional.of(material("BEEF", "10.00", "7.50")));
        when(materials.findByIdAndFactoryId("BOX", "F006")).thenReturn(Optional.of(material("BOX", null, "2.20")));
        when(materials.findByIdAndFactoryId(anyString(), eq("OTHER"))).thenReturn(Optional.empty());
        when(shares.resolve(eq("F006"), any())).thenReturn(Map.of("port-beef", new BigDecimal("100")));
        ReflectionTestUtils.setField(service, "planInputShareResolver", shares);

        List<PlanStandardCostLine> lines = service.buildLines("F006", plan, LocalDateTime.of(2026, 11, 3, 16, 0));

        PlanStandardCostLine beef = lines.stream().filter(l -> "BEEF".equals(l.getMaterialTypeId())).findFirst().orElseThrow();
        assertThat(beef.getUnitPrice()).isEqualByComparingTo("10.00");
        assertThat(beef.getPriceSource()).isEqualTo(PlanStandardCostLine.PRICE_SOURCE_MATERIAL_UNIT_PRICE);
        assertThat(beef.getStandardBasis()).isEqualTo(PlanStandardCostLine.BASIS_RAW_INPUT_SHARE);
        assertThat(beef.getStandardRate()).isEqualByComparingTo("100");
        assertThat(beef.getRawYieldRate()).isEqualByComparingTo("80");
        assertThat(beef.getBomRecipeId()).isEqualTo("R1");
        assertThat(beef.getBomVersion()).isEqualTo(3);

        PlanStandardCostLine box = lines.stream().filter(l -> "BOX".equals(l.getMaterialTypeId())).findFirst().orElseThrow();
        assertThat(box.getUnitPrice()).as("档案没单价就是 null —— ⛔ 不拿采购均价 2.20 顶").isNull();
        assertThat(box.getPriceSource()).isNull();
        assertThat(box.getStandardBasis()).isEqualTo(PlanStandardCostLine.BASIS_PER_OUTPUT_UNIT);
        assertThat(box.getStandardRate()).isEqualByComparingTo("1");
        assertThat(lines).allMatch(l -> "PLAN-1".equals(l.getPlanId()) && l.getCapturedAt() != null);
    }

    @Test
    void planWithoutPinnedRecipeGetsNoSnapshot() {
        ProductionPlan plan = new ProductionPlan();
        plan.setId("PLAN-2");
        plan.setProductTypeId("SKU");
        assertThat(service.buildLines("F006", plan, LocalDateTime.now())).isEmpty();
    }
}
