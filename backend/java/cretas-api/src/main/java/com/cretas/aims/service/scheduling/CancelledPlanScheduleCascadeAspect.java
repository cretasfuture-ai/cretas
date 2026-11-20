package com.cretas.aims.service.scheduling;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 生产计划取消成功之后, 级联收掉它名下没开工的排程任务 (D-S4-CANCEL-GHOST-DAILY)。
 *
 * <p>取消入口 ({@code ProductionPlanService.cancelProductionPlan} 两个重载, 以及审批回调
 * {@code executeCancelApproved}) 不发领域事件, 这里在 Spring 代理上挂「正常返回之后」:
 * 取消抛异常 (被拦 / 回滚) 时不级联。控制器、AI 工具等所有经 Spring Bean 调用取消的入口都会经过这里;
 * 实现类内部 3 参转 4 参的自调用不经代理, 一次取消只级联一次 (重复级联也只会作废 0 条)。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class CancelledPlanScheduleCascadeAspect {

    private final CancelledPlanScheduleCascade cascade;

    @AfterReturning(pointcut = "execution(void com.cretas.aims.service.ProductionPlanService+.cancelProductionPlan(..)) "
            + "&& args(factoryId, planId, ..)", argNames = "factoryId,planId")
    public void afterCancel(String factoryId, String planId) {
        cascade.cascade(factoryId, planId);
    }

    @AfterReturning(pointcut = "execution(void com.cretas.aims.service.ProductionPlanService+.executeCancelApproved(String)) "
            + "&& args(planId)", argNames = "planId")
    public void afterApprovedCancel(String planId) {
        cascade.cascadeByPlanId(planId);
    }
}
