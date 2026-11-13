package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;

/**
 * 1551-11: 待审批的强制插单还没排进去 (批准那一刻才排入并顺延被挤计划), 不能先开工。
 *
 * <p>所有开工入口都过这一道 (单一判定处, ⛔ 不要在入口里另写一份 isPendingApproval 判断):
 * <ul>
 *   <li>{@code ProductionPlanServiceImpl#startProduction} — 计划开工 (网页 / App / AI 的 PlanUpdateTool);</li>
 *   <li>{@code ProductionPlanServiceImpl#createBatchFromPlan} — 网页「逐道录入 / 下发 APP 报工」;</li>
 *   <li>{@code ClerkProcessEntryServiceImpl#createProductionBatch} — 文员录入回写计划状态;</li>
 *   <li>{@code ProcessingServiceImpl#startProduction} — 批次开工 (POST /processing/batches/{id}/start、
 *       AI 的 ProcessingBatchStartTool / BatchStatusChangeTool): 手工批次挂在待审批计划上时。</li>
 * </ul>
 * 普通计划 {@code requiresApproval=false}, 不受影响。调用方必须在任何写入之前调用, 被拦不留半截状态。
 */
public final class PendingApprovalStartGuard {

    public static final String CODE = "PLAN_PENDING_APPROVAL";

    private PendingApprovalStartGuard() {}

    /**
     * @param plan      要开工的计划 (批次开工时是批次所挂的计划); null = 没挂计划, 放行
     * @param approvers 说清「在等谁」的只读查询; null (单测直接 new 的服务) 时用通用说法
     */
    public static void check(ProductionPlan plan, ForceInsertApproverDescriber approvers) {
        if (plan == null || !plan.isPendingApproval()) {
            return;
        }
        String waitingFor = approvers == null ? ForceInsertApproverDescriber.DIRECT_APPROVAL : approvers.describe(plan);
        String planLabel = plan.getPlanNumber() != null && !plan.getPlanNumber().isBlank()
                ? plan.getPlanNumber() : plan.getId();
        throw new BusinessException(409, "计划 " + planLabel + " 是强制插单，还在等" + waitingFor + "，批准后才能开工")
                .withCode(CODE)
                .withHint("请等审批通过后再开工；批准前这张插单还没排进产线，先开工会和被它顺延的计划冲突");
    }
}
