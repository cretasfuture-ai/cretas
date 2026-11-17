package com.cretas.aims.dto.production;

/**
 * 生产计划列表的补充筛选 (原型 /production/plans): 产品 / 单位 / 批量单号。三项都是可选, null/空白 = 不筛。
 *
 * @param productTypeId 产品 (production_plans.product_type_id)
 * @param plannedUnit   计划单位 (production_plans.planned_unit), 精确匹配
 * @param batchNumber   生产批次号 (production_batches.batch_number) 模糊匹配: 计划下任一批次命中即算
 */
public record ProductionPlanListFilter(String productTypeId, String plannedUnit, String batchNumber) {

    public static final ProductionPlanListFilter NONE = new ProductionPlanListFilter(null, null, null);

    public static ProductionPlanListFilter of(String productTypeId, String plannedUnit, String batchNumber) {
        return new ProductionPlanListFilter(blankToNull(productTypeId), blankToNull(plannedUnit),
                blankToNull(batchNumber));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
