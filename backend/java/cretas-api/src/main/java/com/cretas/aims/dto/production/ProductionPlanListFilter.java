package com.cretas.aims.dto.production;

import com.cretas.aims.entity.ProductionBatch;
import com.cretas.aims.entity.ProductionPlan;
import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Locale;

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

    /** LIKE 的转义符 (配合 {@link #escapeLike})。 */
    public static final char LIKE_ESCAPE = '!';

    /** 用户输入当字面量: 转义 ! % _ (ESCAPE '!')。 */
    public static String escapeLike(String v) {
        StringBuilder b = new StringBuilder(v.length() + 4);
        for (char c : v.toCharArray()) {
            if (c == LIKE_ESCAPE || c == '%' || c == '_') b.append(LIKE_ESCAPE);
            b.append(c);
        }
        return b.toString();
    }

    /**
     * 产品 (精确) / 计划单位 (精确) / 批量单号 (计划下任一生产批次的 batch_number 模糊命中, 大小写不敏感;
     * 输入里的 % _ 按字面量匹配)。三项都空 = 不筛。
     */
    public Specification<ProductionPlan> toSpecification() {
        return (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (productTypeId != null) predicates.add(cb.equal(root.get("productTypeId"), productTypeId));
            if (plannedUnit != null) predicates.add(cb.equal(root.get("plannedUnit"), plannedUnit));
            if (batchNumber != null) {
                var sub = query.subquery(Long.class);
                var b = sub.from(ProductionBatch.class);
                sub.select(b.get("id")).where(
                        cb.equal(b.get("productionPlanId"), root.get("id")),
                        cb.like(cb.lower(b.get("batchNumber")),
                                "%" + escapeLike(batchNumber.toLowerCase(Locale.ROOT)) + "%", LIKE_ESCAPE));
                predicates.add(cb.exists(sub));
            }
            return predicates.isEmpty() ? cb.conjunction() : cb.and(predicates.toArray(Predicate[]::new));
        };
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }
}
