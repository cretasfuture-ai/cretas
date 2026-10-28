package com.cretas.aims.service.shortage;

import com.cretas.aims.event.MaterialBatchReleasedEvent;
import com.cretas.aims.event.MaterialReceivedEvent;
import com.cretas.aims.event.SalesOrderFinanceApprovedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 让缺料实例跟着业务事实走: 财务审核通过建 / 刷新, 入库和质检放行刷新含该物料的实例。
 *
 * <p>⛔ 这里的失败只记日志, 不能把审批 / 收货 / 放行本身弄失败 —— 实例下次事件或手动刷新时会追上。
 * 所以监听方法自己<b>不开事务</b>: 刷新在服务里的 REQUIRES_NEW 事务里跑, 失败只回滚那一个事务、在这里被吞掉;
 * 若监听方法也挂 @Transactional, 内层失败会把它标成只能回滚, 没有外层事务时 (放行工具) 那个异常会抛回调用方。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortageCaseListener {

    public static final String REASON_FINANCE_APPROVED = "SO_FINANCE_APPROVED";
    public static final String REASON_MATERIAL_RECEIVED = "MATERIAL_RECEIVED";
    public static final String REASON_BATCH_RELEASED = "MATERIAL_BATCH_RELEASED";

    private final ShortageCaseService caseService;

    @Async
    @EventListener
    public void onSalesOrderFinanceApproved(SalesOrderFinanceApprovedEvent event) {
        try {
            caseService.refreshForSalesOrder(event.getFactoryId(), event.getSalesOrderId(), REASON_FINANCE_APPROVED);
        } catch (Exception e) {
            log.error("[ShortageCase] 财务审核通过后建 / 刷新缺料实例失败 (不影响审批): factory={}, SO={}",
                    event.getFactoryId(), event.getSalesOrderId(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaterialReceived(MaterialReceivedEvent event) {
        refreshMaterial(event.getFactoryId(), event.getMaterialTypeId(), REASON_MATERIAL_RECEIVED);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMaterialBatchReleased(MaterialBatchReleasedEvent event) {
        refreshMaterial(event.getFactoryId(), event.getMaterialTypeId(), REASON_BATCH_RELEASED);
    }

    private void refreshMaterial(String factoryId, String materialTypeId, String reason) {
        if (factoryId == null || materialTypeId == null) {
            return;
        }
        try {
            int n = caseService.refreshForMaterial(factoryId, materialTypeId, reason);
            log.info("[ShortageCase] {} 刷新 {} 个缺料实例: factory={}, material={}", reason, n, factoryId, materialTypeId);
        } catch (Exception e) {
            log.error("[ShortageCase] {} 刷新缺料实例失败 (不影响原操作): factory={}, material={}",
                    reason, factoryId, materialTypeId, e);
        }
    }
}
