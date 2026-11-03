package com.cretas.aims.ai.tool;

import com.cretas.aims.exception.BusinessException;
import com.cretas.aims.service.impl.PermissionServiceImpl;

/**
 * 「待财务审核」状态的单据列表: AI 工具路径与 App 接口同一道二次拦截 (Issue #736)。
 *
 * <p>SalesController / PurchaseController 的按状态查询接口对 PENDING_FINANCE_REVIEW 另走
 * {@code procurement:finance_review:view} 白名单; 工具直接调服务, controller 的这道拦截不运行。
 * 对话里说「待财务审核的销售订单」会被认成这个状态 (ERP-S6-10 之后), 所以工具路径必须同样拦。
 */
public final class FinanceReviewStatusGuard {

    public static final String STATUS = "PENDING_FINANCE_REVIEW";

    private FinanceReviewStatusGuard() {
    }

    /**
     * @param status 工具要按它筛的状态代码 (可为 null)
     * @param roleCode 调用者角色码
     * @param what 「销售单」/「采购单」
     * @throws BusinessException 403 —— 状态是待财审而角色不在白名单
     */
    public static void check(String status, String roleCode, String what) {
        if (status != null && STATUS.equalsIgnoreCase(status.trim())
                && !PermissionServiceImpl.canViewFinanceReview(roleCode)) {
            throw new BusinessException(403, "无权查看待财审" + what)
                    .withHint("待财务审核的" + what + "只有财务 / 采购主管 / 工厂管理员等能看");
        }
    }
}
