package com.cretas.aims.service.processsecret;

import com.cretas.aims.entity.ProductionPlan;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 当前调用者能看的计划成本 (Steve 10-29: 无权的人看到的合计只算他能看的行, 不做「总数 − 保密」)。
 * 生产报表与仪表盘共用这一份 (两处各写一遍会漂)。
 *
 * <p>口径与 {@code ProductionPlanRepository.calculate*CostBetweenDates} 一致: 结束时间在区间内 (含端点);
 * 分项为 SUM 语义 (无行 / 全空为 null), 合计为 SUM(COALESCE(四项, 0)) 语义 (无行为 null)。
 * {@code perDayBounds}: 与按天查询一致, 每天只算到当天 23:59:59。</p>
 */
public record ProcessSecretPlanCosts(BigDecimal material, BigDecimal labor, BigDecimal equipment, BigDecimal other,
                                     BigDecimal total, Map<LocalDate, BigDecimal> totalByDay,
                                     int excludedPlans, int excludedProducts) {

    /** 有没有计划因为保密被排除 (有 = 合计不完整, 要带说明; 营收含保密产品时利润不可算)。 */
    public boolean partial() {
        return excludedPlans > 0;
    }

    public String note() {
        return partial() ? ProcessSecretLlmCostScope.note(excludedProducts) : null;
    }

    /** 已经按调用方口径筛好的计划行 (不再按结束时间筛窗口)。 */
    public static ProcessSecretPlanCosts ofRows(ProcessSecretLlmCostScope.Excluded excluded, List<ProductionPlan> plans) {
        return of(excluded, plans, null, null, false);
    }

    /** {@code from} / {@code to} 为 null = 不筛窗口。 */
    public static ProcessSecretPlanCosts of(ProcessSecretLlmCostScope.Excluded excluded, List<ProductionPlan> plans,
                                            LocalDateTime from, LocalDateTime to, boolean perDayBounds) {
        Map<LocalDate, BigDecimal> byDay = new TreeMap<>();
        BigDecimal material = null, labor = null, equipment = null, other = null, total = null;
        Set<String> excludedProducts = new LinkedHashSet<>();
        int excludedPlans = 0;
        for (ProductionPlan plan : plans) {
            LocalDateTime end = plan.getEndTime();
            if (from != null && (end == null || end.isBefore(from) || end.isAfter(to))) continue;
            if (perDayBounds && end.isAfter(end.toLocalDate().atTime(23, 59, 59))) continue;
            if (excluded.coversPlan(plan.getId()) || excluded.coversProduct(plan.getProductTypeId())) {
                excludedPlans++;
                // N 只数真正受保护的产品 (计划锚点 / 同计划普通产品不算)
                excludedProducts.addAll(excluded.protectedBehind(plan.getProductTypeId(),
                        plan.getId() == null ? List.of() : List.of(plan.getId())));
                continue;
            }
            material = sumNullable(material, plan.getActualMaterialCost());
            labor = sumNullable(labor, plan.getActualLaborCost());
            equipment = sumNullable(equipment, plan.getActualEquipmentCost());
            other = sumNullable(other, plan.getActualOtherCost());
            BigDecimal cost = nz(plan.getActualMaterialCost()).add(nz(plan.getActualLaborCost()))
                    .add(nz(plan.getActualEquipmentCost())).add(nz(plan.getActualOtherCost()));
            total = total == null ? cost : total.add(cost);
            if (end != null) byDay.merge(end.toLocalDate(), cost, BigDecimal::add);
        }
        return new ProcessSecretPlanCosts(material, labor, equipment, other, total, byDay, excludedPlans,
                excludedProducts.size());
    }

    private static BigDecimal sumNullable(BigDecimal acc, BigDecimal value) {
        return value == null ? acc : acc == null ? value : acc.add(value);
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
