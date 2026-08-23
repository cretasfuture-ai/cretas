package com.cretas.aims.service.orchestration;

import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.service.inventory.TransferService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Closes only uncompleted, plan-linked preparation transfers after formal completion.
 *
 * <p>🔴 2026-08-19: 这里原来有一个 {@code private static final long SYSTEM_USER_ID = 0L}
 * 当关单人。prod 的 {@code users} 表里没有 id=0（79 行，{@code min(id) = 1309}）——
 * 那个 0 从来不指向任何人。它顺着
 * {@code closeOpenTransfersForProductionPlan → workflowEngine.cancel} 流进
 * {@code approval_history.actor_id}（可空、无外键，所以不炸，只是留下一条悬空引用）。
 *
 * <p>⛔ 修法不是换一个更好的写死 id —— 写死的 user id 有保质期。
 * 让计划完工的那个人就在 {@link ProductionCompletedEvent#getCompletedBy()} 里，直接用它；
 * 为 null 时<b>原样传 null</b>（该列可空，null 是合法值，而 0 是假的）。
 *
 * @see ProductionCompletedActorPropagationTest
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ProductionPlanTransferLifecycleListener {

    private final TransferService transferService;

    @EventListener
    public void onProductionCompleted(ProductionCompletedEvent event) {
        int closed = transferService.closeOpenTransfersForProductionPlan(
                event.getFactoryId(), event.getPlanId(), event.getCompletedBy(),
                "生产计划已正式完成，关闭未完成的备料调拨关联");
        if (closed > 0) {
            log.info("Closed {} open rolling transfers for completed plan {} (closedBy={})",
                    closed, event.getPlanId(), event.getCompletedBy());
        }
    }
}
