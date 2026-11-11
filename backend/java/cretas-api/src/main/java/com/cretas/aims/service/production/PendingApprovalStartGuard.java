package com.cretas.aims.service.production;

import com.cretas.aims.entity.ProductionPlan;
import com.cretas.aims.exception.BusinessException;

/**
 * 1551-11: 待审批的强制插单还没排进去 (批准那一刻才排入并顺延被挤计划), 不能先开工。
 *
 * <p>把计划从「待处理」翻成「生产中」的入口都要过这一道 (单一判定处):
 * {@code ProductionPlanServiceImpl#startProduction}、{@code ProductionPlanServiceImpl#createBatchFromPlan}
 * (网页「逐道录入 / 下发 APP 报工」)、{@code ClerkProcessEntryServiceImpl} 文员录入回写计划状态。
 * 普通计划 {@code requiresApproval=false}, 不受影响。
 */
public final class PendingApprovalStartGuard {

    public static final String CODE = "PLAN_PENDING_APPROVAL";

    private PendingApprovalStartGuard() {}

    public static void check(ProductionPlan plan) {
        if (plan != null && plan.isPendingApproval()) {
            throw new BusinessException(409, "这张强制插单还在审批中，批准后才能开工")
                    .withCode(CODE)
                    .withHint("请等审批人在审批中心批准");
        }
    }
}
