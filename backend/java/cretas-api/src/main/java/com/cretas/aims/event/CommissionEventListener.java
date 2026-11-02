package com.cretas.aims.event;

import com.cretas.aims.service.CommissionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Sprint 7 wave 2 Track T5 — 提成自动计算 listener.
 *
 * <p>触发: {@link OpportunityClosedWonEvent} (在 SalesOpportunity stage 转为
 * CLOSED_WON 时由 {@code SalesOpportunityService} 发布).
 *
 * <p>使用 {@code @TransactionalEventListener(AFTER_COMMIT)} 确保 opportunity 事务提交后
 * 才计算提成, 避免回滚导致脏数据.
 *
 * <p>Best-effort: listener 失败仅 log, 不影响 caller. CommissionService.calculate
 * 本身 idempotent, 即使重复触发也安全.
 *
 * <p>{@code NOT_SUPPORTED} 不能去掉: AFTER_COMMIT 回调时, 刚提交的赢单事务的资源仍绑在线程上,
 * {@code calculate} 的 {@code @Transactional}(REQUIRED) 会「加入」这个已结束的事务, {@code save}
 * 进了持久化上下文却再没人 flush —— 提成静默丢失, 不抛异常, 日志照打「Calculated commission」。
 * 挂起之后 {@code calculate} 自己开事务并提交。守门测试:
 * {@code CommissionEventListenerAfterCommitContractTest}。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionEventListener {

    private final CommissionService commissionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void onClosedWon(OpportunityClosedWonEvent event) {
        log.info("Received OpportunityClosedWonEvent for opp={}, calculating commission",
                event.getOpportunityId());
        try {
            commissionService.calculate(event.getOpportunityId());
        } catch (Exception e) {
            log.error("Failed to calculate commission for opportunity {}",
                    event.getOpportunityId(), e);
        }
    }
}
