package com.cretas.aims.service.processsecret;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 发给大模型的成本数据里不带工艺保密产品 (Steve 10-28: 「对无授权账号和 AI 一律不带」)。
 *
 * <p>与调用者无关: 保密负责人 / 被授权人发起的 AI 分析同样不带, 只有授权人在非 AI 页面能看。
 * 数量可以留, 成本金额 (原料/辅料/包材小计、总成本、单位成本、毛利、毛利率) 不进提示词。
 * 全厂合计只在<b>非保密行</b>上逐行累加 (不做「总数 − 保密」), 并注明「不含 N 个保密产品」。</p>
 *
 * <p>范围 = 本厂全部受保护产品 + 牵涉它们的生产计划 ({@link ProcessSecretAggregateExclusions#allProtected}):
 * 多产出计划的锚点是原料 id, 只按产品排除会漏。取不到范围时直接抛出, 调用方据此不发 (fail-closed)。</p>
 *
 * <p>「不含 N 个保密产品」的 N 只数真正受保护的产品 ({@link Excluded#protectedProductTypeIds()}),
 * 计划锚点 (原料 id) 和同计划的普通产品虽然被一并排除, 但不算进 N。</p>
 */
@Component
public class ProcessSecretLlmCostScope {

    public static final String WITHHELD_REASON = "该产品工艺已设为保密，成本数据不提供给 AI 分析";

    /**
     * 本厂对 AI 一律排除的范围。{@code productTypeIds} / {@code planIds} 是排除范围 (含计划锚点),
     * {@code protectedProductTypeIds} 只是真正受保护的产品, {@code protectedProductsByPlan} 是每个被排除计划背后的保密产品。
     */
    public record Excluded(Set<String> productTypeIds, Set<String> planIds, Set<String> protectedProductTypeIds,
                           Map<String, Set<String>> protectedProductsByPlan) {
        public static final Excluded NONE = new Excluded(Set.of(), Set.of(), Set.of(), Map.of());

        public boolean isEmpty() {
            return productTypeIds.isEmpty() && planIds.isEmpty();
        }

        public boolean coversProduct(String productTypeId) {
            return productTypeId != null && productTypeIds.contains(productTypeId);
        }

        public boolean coversPlan(String planId) {
            return planId != null && planIds.contains(planId);
        }

        /** 批次: 产品或所属计划牵涉受保护产品即排除; 本厂有保密产品而这一行认不出产品时同样排除 (fail-closed)。 */
        public boolean coversBatch(String productTypeId, String planId) {
            if (isEmpty()) return false;
            if (productTypeId == null || productTypeId.isBlank()) return true;
            return coversProduct(productTypeId) || coversPlan(planId);
        }

        /**
         * 产品级成本 (由多个批次累加): 产品本身在范围内, 或任一来源批次的计划牵涉保密产品, 就整份不给。
         * 来源计划未知 ({@code null}) 而本厂有保密产品时同样不给 (fail-closed)。
         */
        public boolean coversProductOrAnyPlan(String productTypeId, Collection<String> sourcePlanIds) {
            if (isEmpty()) return false;
            if (productTypeId == null || coversProduct(productTypeId) || sourcePlanIds == null) return true;
            return sourcePlanIds.stream().anyMatch(this::coversPlan);
        }

        /** 一行被排除时, 它背后是哪几个真正受保护的产品 (计数用; 锚点 / 普通产品不算)。 */
        public Set<String> protectedBehind(String productTypeId, Collection<String> planIds) {
            Set<String> behind = new LinkedHashSet<>();
            if (productTypeId != null && protectedProductTypeIds.contains(productTypeId)) behind.add(productTypeId);
            if (planIds != null) {
                for (String planId : planIds) {
                    if (planId != null) behind.addAll(protectedProductsByPlan.getOrDefault(planId, Set.of()));
                }
            }
            return behind;
        }
    }

    /** 去掉保密行之后剩下的行, 以及去掉了几行、涉及几个真正受保护的产品 (给「不含 N 个保密产品」用)。 */
    public record Rows(List<Map<String, Object>> allowed, int excludedRows, int excludedProducts) {
        public boolean anyExcluded() {
            return excludedRows > 0;
        }

        /** 没排除就是空串; 排除了就是一句带「不含 N 个保密产品」的说明。 */
        public String note() {
            return excludedRows == 0 ? "" : ProcessSecretLlmCostScope.note(excludedProducts);
        }
    }

    /** N = 真正受保护的产品个数; 认不出是哪个保密产品 (N=0 但确有行被排除) 时不报数字。 */
    public static String note(int excludedProducts) {
        if (excludedProducts <= 0) {
            return "说明：以上成本与合计不含工艺保密产品相关的数据（工艺保密产品的成本不提供给 AI）。";
        }
        return "说明：以上成本与合计不含 " + excludedProducts + " 个保密产品（工艺保密产品的成本不提供给 AI）。";
    }

    private final ProcessSecretAggregateExclusions exclusions;
    private final Excluded fixed;

    @Autowired
    public ProcessSecretLlmCostScope(ProcessSecretAggregateExclusions exclusions) {
        this.exclusions = exclusions;
        this.fixed = null;
    }

    private ProcessSecretLlmCostScope(Excluded fixed) {
        this.exclusions = null;
        this.fixed = fixed;
    }

    /** 单测里裸 new 出来的服务用的默认实例 (与 LlmEgressGuard.standalone() 同款): 没有保密产品。生产一律由 Spring 注入。 */
    public static ProcessSecretLlmCostScope standalone() {
        return new ProcessSecretLlmCostScope(Excluded.NONE);
    }

    public Excluded forFactory(String factoryId) {
        if (fixed != null) return fixed;
        if (factoryId == null || factoryId.isBlank()) {
            throw new IllegalStateException("工厂未识别，无法判定工艺保密范围，成本数据不发给 AI");
        }
        ProcessSecretAggregateExclusions.ProtectedScope detailed = exclusions.allProtectedDetailed(factoryId);
        return new Excluded(Set.copyOf(detailed.scope().productTypeIds()), Set.copyOf(detailed.scope().planIds()),
                detailed.protectedProductTypeIds(), detailed.protectedProductsByPlan());
    }

    /** 本厂有没有任何保密产品 (共享的 AI 分析缓存、存档报告只在没有时才读进提示词)。 */
    public boolean factoryHasProtected(String factoryId) {
        return !forFactory(factoryId).isEmpty();
    }

    public boolean isProtectedBatch(String factoryId, String productTypeId, String planId) {
        return forFactory(factoryId).coversBatch(productTypeId, planId);
    }

    /**
     * 批次行 (每行带 {@code productTypeId} / {@code productionPlanId}) 去掉保密的整行。
     * 合计由调用方在 {@link Rows#allowed()} 上逐行累加。
     */
    public Rows withoutProtectedBatches(String factoryId, List<Map<String, Object>> rows) {
        if (rows == null || rows.isEmpty()) return new Rows(List.of(), 0, 0);
        Excluded excluded = forFactory(factoryId);
        if (excluded.isEmpty()) return new Rows(new ArrayList<>(rows), 0, 0);
        List<Map<String, Object>> allowed = new ArrayList<>(rows.size());
        Set<String> products = new LinkedHashSet<>();
        int excludedRows = 0;
        for (Map<String, Object> row : rows) {
            String productTypeId = text(row.get("productTypeId"));
            String planId = text(row.get("productionPlanId"));
            if (excluded.coversBatch(productTypeId, planId)) {
                excludedRows++;
                products.addAll(excluded.protectedBehind(productTypeId, planId == null ? List.of() : List.of(planId)));
            } else {
                allowed.add(row);
            }
        }
        return new Rows(allowed, excludedRows, products.size());
    }

    /**
     * 中央兜底: 发给模型的结构化数据里, 任何一层出现属于保密范围的 {@code productTypeId} /
     * {@code productionPlanId} 就返回 true —— 说明调用方漏了过滤, 整次不发。
     */
    public boolean containsProtected(String factoryId, Object data) {
        Excluded excluded = forFactory(factoryId);
        if (excluded.isEmpty()) return false;
        return scan(excluded, data, 0);
    }

    private static boolean scan(Excluded excluded, Object node, int depth) {
        if (node == null || depth > 12) return false;
        if (node instanceof Map<?, ?> map) {
            if (excluded.coversProduct(text(map.get("productTypeId")))
                    || excluded.coversPlan(text(map.get("productionPlanId")))) {
                return true;
            }
            for (Object value : map.values()) {
                if (scan(excluded, value, depth + 1)) return true;
            }
            return false;
        }
        if (node instanceof Collection<?> items) {
            for (Object item : items) {
                if (scan(excluded, item, depth + 1)) return true;
            }
        }
        return false;
    }

    private static String text(Object value) {
        if (value == null) return null;
        String s = value.toString();
        return s.isBlank() ? null : s;
    }
}
