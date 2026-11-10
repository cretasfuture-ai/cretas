package com.cretas.aims.service.scheduling;

import com.cretas.aims.repository.scheduling.CancelledPlanScheduleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 生产计划取消后, 收掉它名下的排程任务 (D-S4-CANCEL-GHOST-DAILY)。
 *
 * <p>没开工的 (pending 且没有实际开始时间) 置为 cancelled, 写明原因; 已开工的不动 —— 人和设备真在线上,
 * 由调度计划里手工收尾。⛔ 不删行。由 {@link CancelledPlanScheduleCascadeAspect} 挂在
 * {@code ProductionPlanService} 的取消入口之后调用 (取消实现本身不在这里改)。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CancelledPlanScheduleCascade {

    private final CancelledPlanScheduleRepository repository;

    /** 这个生产计划名下: 被取消级联作废的排程条数 / 已开工保留未动的条数。 */
    public record Outcome(long cancelledSchedules, long keptStartedSchedules) {

        /** 接在「生产计划已取消」后面说给用户的话。 */
        public String describe(String head) {
            StringBuilder sb = new StringBuilder(head);
            if (cancelledSchedules > 0) {
                sb.append("；").append(cancelledSchedules).append(" 条未开工的排程任务已一并取消");
            }
            if (keptStartedSchedules > 0) {
                sb.append("；").append(keptStartedSchedules)
                        .append(" 条排程任务已开工，保留未动，请到调度计划里收尾");
            }
            return sb.toString();
        }
    }

    /**
     * 级联。只在这个生产计划此刻在库里已是「已取消」时才动 (条件写在 UPDATE 里); 重复调用只会作废 0 条。
     * 有外层事务 (取消本身的事务) 就加入, 与取消同进同退; 没有就自己开一个。
     */
    @Transactional
    public Outcome cascade(String factoryId, String productionPlanId) {
        int cancelled = repository.cancelNotStartedOfCancelledPlan(factoryId, productionPlanId, LocalDateTime.now());
        long kept = repository.countStartedNotFinished(factoryId, productionPlanId);
        log.info("生产计划取消级联排程: factoryId={}, planId={}, 作废未开工={}, 已开工保留={}",
                factoryId, productionPlanId, cancelled, kept);
        return new Outcome(cancelled, kept);
    }

    /** 审批回调入口只带计划 id: 工厂从库里读。读不到计划就是没有可级联的。 */
    @Transactional
    public void cascadeByPlanId(String productionPlanId) {
        repository.findFactoryIdOfPlan(productionPlanId)
                .ifPresent(factoryId -> cascade(factoryId, productionPlanId));
    }

    /** 取消之后给用户的结果: 现数, 不依赖级联那一刻的返回值。 */
    @Transactional(readOnly = true)
    public Outcome summarize(String factoryId, String productionPlanId) {
        return new Outcome(repository.countCascadeCancelled(factoryId, productionPlanId),
                repository.countStartedNotFinished(factoryId, productionPlanId));
    }
}
