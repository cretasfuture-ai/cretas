package com.cretas.aims.service.orchestration;

import com.cretas.aims.event.ProductionCompletedEvent;
import com.cretas.aims.service.inventory.TransferService;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class ProductionPlanTransferLifecycleListenerTest {

    @Test
    void finalProductionCompletion_closesOnlyOpenTransfersLinkedToThePlan() {
        TransferService transferService = mock(TransferService.class);
        ProductionPlanTransferLifecycleListener listener =
                new ProductionPlanTransferLifecycleListener(transferService);
        ProductionCompletedEvent event = new ProductionCompletedEvent(
                this, "F006", "plan-1", "PLAN-001", "sku-1", new BigDecimal("5"), 1309L);

        listener.onProductionCompleted(event);

        // 🔴 2026-08-19: 这条断言原来钉的是字面量 0L —— 它没有恒真, 也没有失效,
        //    它好好地守着一个我们现在判定为缺陷的行为 (形态 C‴)。
        //    prod 的 users 表里没有 id=0 (min(id)=1309), 那个 0 从来不指向任何人。
        //    ⇒ 把它从「守字面」抬到「守性质」: 关单人就是让计划完工的那个人。
        verify(transferService).closeOpenTransfersForProductionPlan(
                "F006", "plan-1", 1309L, "生产计划已正式完成，关闭未完成的备料调拨关联");

        // 阴性对照: 那个写死的 0 不许回来。
        // ⚠️ 其余参数用 any() 而不是具体值 —— anyLong() 不匹配 null, 本仓 2026-08-15
        //    因此写出过一条两条路都成立的阴性断言。
        verify(transferService, never()).closeOpenTransfersForProductionPlan(
                any(), any(), eq(0L), any());
    }
}
