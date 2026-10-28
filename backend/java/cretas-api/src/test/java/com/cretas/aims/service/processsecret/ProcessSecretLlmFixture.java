package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.repository.ProductionPlanRepository;
import com.cretas.aims.repository.bom.BomRecipeRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「发给大模型的成本数据不带保密产品」各出口测试共用的数据: 一个保密产品 + 两个普通产品,
 * 成本数字各不相同且一眼可辨 (保密 7777.77, 普通 1234.56 / 4321.00), 断言直接在发给模型的字符串上找。
 *
 * <p>保密范围走真实的 {@link ProcessSecretLlmCostScope} → {@link ProcessSecretAggregateExclusions#allProtected},
 * 只桩掉「本厂有哪些保密产品」和「本厂有哪些计划」两张表。</p>
 */
public final class ProcessSecretLlmFixture {

    public static final String FACTORY = "F-PS";
    public static final String SECRET = "P-SECRET";
    public static final String NORMAL = "P-NORMAL";
    public static final String NORMAL2 = "P-NORMAL2";
    public static final String SECRET_PLAN = "PL-SECRET";
    public static final String NORMAL_PLAN = "PL-NORMAL";
    public static final String SECRET_NAME = "保密卤味";

    /** 保密产品的成本: 任何发给模型的字符串里都不许出现。 */
    public static final BigDecimal SECRET_COST = new BigDecimal("7777.77");
    public static final BigDecimal NORMAL_COST = new BigDecimal("1234.56");
    public static final BigDecimal NORMAL2_COST = new BigDecimal("4321.00");

    private ProcessSecretLlmFixture() {
    }

    /** 真实的保密范围; {@code protectedIds} 为空 = 本厂没有保密产品 (阴性对照)。 */
    public static ProcessSecretLlmCostScope scope(Set<String> protectedIds) {
        return scope(protectedIds, plans());
    }

    public static ProcessSecretLlmCostScope scope(Set<String> protectedIds, List<ProductionPlan> plans) {
        // lenient: 在 MockitoExtension 的严格模式里用时, 没用到的桩不算错 (阴性对照不会查计划)
        ProcessSecretAccessService access = mock(ProcessSecretAccessService.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(access.protectedProductTypeIds(anyString())).thenReturn(protectedIds);
        ProductionPlanRepository planRepository = mock(ProductionPlanRepository.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(planRepository.findByFactoryId(anyString())).thenReturn(plans);
        BomRecipeRepository recipes = mock(BomRecipeRepository.class, org.mockito.Mockito.withSettings().strictness(org.mockito.quality.Strictness.LENIENT));
        when(recipes.findAllById(any())).thenReturn(List.of());
        return new ProcessSecretLlmCostScope(new ProcessSecretAggregateExclusions(access, planRepository, recipes));
    }

    public static ProcessSecretLlmCostScope protectedScope() {
        return scope(Set.of(SECRET));
    }

    public static ProcessSecretLlmCostScope unprotectedScope() {
        return scope(Set.of());
    }

    public static List<ProductionPlan> plans() {
        return List.of(plan(SECRET_PLAN, SECRET, null), plan(NORMAL_PLAN, NORMAL, null));
    }

    public static ProductionPlan plan(String id, String productTypeId, LocalDateTime endTime) {
        ProductionPlan plan = new ProductionPlan();
        plan.setId(id);
        plan.setFactoryId(FACTORY);
        plan.setProductTypeId(productTypeId);
        plan.setEndTime(endTime);
        return plan;
    }

    public static ProductionBatch batch(long id, String productTypeId, String planId, BigDecimal totalCost,
                                        BigDecimal quantity) {
        ProductionBatch batch = new ProductionBatch();
        batch.setId(id);
        batch.setFactoryId(FACTORY);
        batch.setBatchNumber("B-" + id);
        batch.setProductTypeId(productTypeId);
        batch.setProductionPlanId(planId);
        batch.setProductName(SECRET.equals(productTypeId) ? SECRET_NAME : "普通" + id);
        batch.setTotalCost(totalCost);
        batch.setMaterialCost(totalCost.multiply(new BigDecimal("0.6")));
        batch.setLaborCost(totalCost.multiply(new BigDecimal("0.3")));
        batch.setEquipmentCost(totalCost.multiply(new BigDecimal("0.1")));
        batch.setActualQuantity(quantity);
        batch.setCreatedAt(LocalDateTime.of(2026, 10, 20, 9, 0));
        return batch;
    }

    /** 与 ProcessingServiceImpl.getWeeklyBatchesCost 同形的批次成本摘要行 (含 productTypeId / productionPlanId)。 */
    public static Map<String, Object> weeklyRow(long batchId, String productTypeId, String planId, BigDecimal totalCost) {
        Map<String, Object> row = new HashMap<>();
        row.put("batchId", batchId);
        row.put("batchNumber", "B-" + batchId);
        row.put("productName", SECRET.equals(productTypeId) ? SECRET_NAME : "普通" + batchId);
        row.put("productTypeId", productTypeId);
        row.put("productionPlanId", planId);
        row.put("status", "COMPLETED");
        row.put("createdAt", LocalDateTime.of(2026, 10, 20, 9, 0));
        Map<String, Object> costSummary = new HashMap<>();
        costSummary.put("totalCost", totalCost);
        costSummary.put("unitCost", totalCost.divide(new BigDecimal("100")));
        row.put("costSummary", costSummary);
        row.put("actualQuantity", new BigDecimal("100"));
        row.put("yieldRate", new BigDecimal("95"));
        return row;
    }

    /** 一保密两普通的批次成本摘要行。 */
    public static List<Map<String, Object>> weeklyRows() {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(weeklyRow(1L, SECRET, SECRET_PLAN, SECRET_COST));
        rows.add(weeklyRow(2L, NORMAL, NORMAL_PLAN, NORMAL_COST));
        rows.add(weeklyRow(3L, NORMAL2, null, NORMAL2_COST));
        return rows;
    }

    /** 测试里逐行算出的「非保密行合计」: 不是「总数 − 保密」。 */
    @SuppressWarnings("unchecked")
    public static BigDecimal sumOfNonProtectedRows(List<Map<String, Object>> rows) {
        BigDecimal sum = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            if (SECRET.equals(row.get("productTypeId")) || SECRET_PLAN.equals(row.get("productionPlanId"))) continue;
            sum = sum.add((BigDecimal) ((Map<String, Object>) row.get("costSummary")).get("totalCost"));
        }
        return sum;
    }
}
